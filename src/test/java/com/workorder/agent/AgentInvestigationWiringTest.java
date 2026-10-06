package com.workorder.agent;

import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.common.PageResult;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 接线第一片：**受理 → 工具 → 校验 → 渲染**贯通（不含 HTTP controller、异步、超时链与取消）。
 *
 * <p>本机可做的部分：开关打开后 bean 全部装配；**用真工具打到 `work_order_test`**；模型走**本地 HTTP 桩**
 * （保留真实 HTTP 与 JSON 解析路径）。**刻意避开需要 Redis 的路径**：不登录取会话、不用编号生成器
 * （本机 6379/5672 未起，混进来只会把环境失败伪装成实现失败）。
 */
@SpringBootTest(properties = "agent.investigation.enabled=true")
@ActiveProfiles("test")
@Transactional
@DisplayName("S4 接线：受理 → 工具 → 校验 → 渲染")
class AgentInvestigationWiringTest {

    /** 静态桩：URL 必须在 Spring 容器启动前确定（`@DynamicPropertySource`），脚本再按用例排。 */
    private static final StubModelServer MODEL = new StubModelServer();

    @DynamicPropertySource
    static void llmProperties(DynamicPropertyRegistry registry) {
        registry.add("llm.api.url", MODEL::url);
        registry.add("llm.api.key", () -> "stub-key");
        registry.add("llm.api.model", () -> "stub-model");
    }

    @AfterAll
    static void closeModel() {
        MODEL.close();
    }

    private static final long DEPT_A = 7001L;
    private static final long DEPT_B = 7002L;
    private static final long USER_A = 9001L;       // 部门 A 的主管
    private static final long USER_B = 9002L;       // 部门 B 的用户
    private static final long DEPT_ADMIN_ROLE_ID = 4L;
    /** 工单号必须符合 `WO-yyyyMMdd-nnnnn`：否则**透明规则认不出它**，基线会把问题判成 UNSUPPORTED（本轮实测踩到）。 */
    private static final java.util.concurrent.atomic.AtomicInteger ORDER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(90000);

    @Autowired
    private AgentInvestigationService service;
    @Autowired
    private InvestigationAgent agent;
    @Autowired
    private FixedFlowInvestigator fixed;
    @Autowired
    private AgentReportRenderer renderer;
    @Autowired
    private WorkOrderService workOrderService;
    @Autowired
    private WorkOrderMapper workOrderMapper;
    @Autowired
    private WorkOrderLogMapper workOrderLogMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;

    @Test
    @DisplayName("开关打开 → 受理层与全部零件装配成功（默认 mode=fixed）")
    void beansAreWired() {
        assertNotNull(service);
        assertNotNull(agent);
        assertNotNull(fixed);
        assertNotNull(renderer);
        assertEquals("fixed", service.mode());
    }

    @Test
    @DisplayName("部门判定同源：列表口径与受理层范围来自同一个方法")
    void departmentScopeComesFromTheSameMethod() {
        insertUser(USER_A, "dept-a-admin", DEPT_A);
        insertUser(USER_B, "dept-b-user", DEPT_B);
        bindRole(USER_A, DEPT_ADMIN_ROLE_ID);
        WorkOrder mine = insertOrder(USER_A, "IN_PROGRESS");
        insertOrder(USER_B, "IN_PROGRESS");

        assertEquals(Set.of(USER_A), Set.copyOf(workOrderService.departmentMemberIds(DEPT_A)),
                "共享方法返回该部门成员 id");

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(20);
        PageResult<WorkOrderVO> listed = workOrderService.listOrders(query, USER_A);

        assertTrue(listed.getRecords().stream().anyMatch(vo -> mine.getOrderNo().equals(vo.getOrderNo())),
                "列表能看到本部门提交的单：" + listed.getRecords());
        assertFalseOtherDepartmentVisible(listed);
    }

    @Test
    @DisplayName("整链贯通（mode=fixed）：受理 → 真工具（test 库）→ 校验 → 渲染出三段报告")
    void fixedModeEndToEndProducesRenderedReport() {
        insertUser(USER_A, "dept-a-admin", DEPT_A);
        WorkOrder order = insertOrder(USER_A, "IN_PROGRESS");
        insertLog(order);

        AgentInvestigationService.Outcome outcome = service.investigate(USER_A, "工单 " + order.getOrderNo() + " 现在到哪一步了？");

        assertEquals("COMPLETED", outcome.status(), () -> "failure=" + outcome.failureCode());
        assertNotNull(outcome.report());
        assertNotNull(outcome.renderedText());
        for (String section : List.of("【已核实事实】", "【证据缺口】", "【下一步核实建议】")) {
            assertTrue(outcome.renderedText().contains(section), outcome.renderedText());
        }
    }

    @Test
    @DisplayName("非本部门用户受理 → 越权路径为 FORBIDDEN，且不产出报告与文本")
    void otherDepartmentIsForbidden() {
        insertUser(USER_A, "dept-a-admin", DEPT_A);
        insertUser(USER_B, "dept-b-user", DEPT_B);
        WorkOrder other = insertOrder(USER_B, "IN_PROGRESS");

        AgentInvestigationService.Outcome outcome = service.investigate(USER_A, "工单 " + other.getOrderNo() + " 现在到哪一步了？");

        assertEquals("FAILED", outcome.status());
        assertEquals("FORBIDDEN", outcome.failureCode());
        assertNull(outcome.report());
        assertNull(outcome.renderedText(), "失败不得产出任何文本");
    }

    @Test
    @DisplayName("mode=fixed 与 mode=agent 都能跑通，且共用同一套工具/校验/渲染（报告结构一致）")
    void bothModesShareTheSamePartsAndProduceComparableReports() {
        insertUser(USER_A, "dept-a-admin", DEPT_A);
        WorkOrder order = insertOrder(USER_A, "IN_PROGRESS");
        insertLog(order);
        String question = "工单 " + order.getOrderNo() + " 现在到哪一步了？";

        AgentInvestigationService.Outcome fixedOutcome = service.investigate(USER_A, question);

        // agent 模式：用**同一个** bean 集合新构造一个受理层实例（同一工具/校验/渲染），模型走本地桩
        MODEL.enqueue(StubModelServer.json(StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + order.getOrderNo() + "\"}")),
                // 这张单**未分配** → 按 §11-4 禁止项不得建议"联系处理人"；模型侧也必须选这条（基线同样如此）
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("ESCALATE_TO_DEPT_ADMIN"))));
        AgentInvestigationService agentMode = new AgentInvestigationService(agent, fixed, renderer, "agent",
                workOrderService);
        AgentInvestigationService.Outcome agentOutcome = agentMode.investigate(USER_A, question);

        assertEquals("COMPLETED", fixedOutcome.status(), () -> "fixed failure=" + fixedOutcome.failureCode());
        assertEquals("COMPLETED", agentOutcome.status(), () -> "agent failure=" + agentOutcome.failureCode()
                + " requests=" + MODEL.requestCount() + " violations=" + MODEL.protocolViolations());
        assertEquals(fixedOutcome.report().problemType(), agentOutcome.report().problemType(),
                "同一问题应落到同一问题类型");
        for (String section : List.of("【已核实事实】", "【证据缺口】", "【下一步核实建议】")) {
            assertTrue(fixedOutcome.renderedText().contains(section));
            assertTrue(agentOutcome.renderedText().contains(section));
        }
    }

    // ---------- 夹具（不触碰 Redis：不用编号生成器、不登录） ----------

    private void insertUser(long id, String username, long deptId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe");
        user.setDeptId(deptId);
        user.setStatus(1);
        userMapper.insert(user);
    }

    private void bindRole(long userId, long roleId) {
        UserRole binding = new UserRole();
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        userRoleMapper.insert(binding);
    }

    private WorkOrder insertOrder(long submitterId, String status) {
        WorkOrder order = new WorkOrder();
        order.setOrderNo(String.format("WO-20261006-%05d", ORDER_SEQ.incrementAndGet()));
        order.setTitle("接线测试工单");
        order.setContent("S4 接线第一片：受理 → 工具 → 校验 → 渲染");
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus(status);
        order.setSubmitterId(submitterId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setTriageStatus("DONE");
        order.setVersion(0);
        order.setSlaDeadline(LocalDateTime.now().plusDays(1));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(order);
        return order;
    }

    private void insertLog(WorkOrder order) {
        WorkOrderLog log = new WorkOrderLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setOperatorId(USER_A);
        log.setAction("SUBMIT");
        log.setNewStatus("PENDING");
        log.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(log);
    }

    /** 列表里不得出现别的部门的单——与 `departmentMemberIds` 同源才能成立。 */
    private static void assertFalseOtherDepartmentVisible(PageResult<WorkOrderVO> listed) {
        assertTrue(listed.getRecords().stream().noneMatch(vo -> vo.getSubmitterId() != null
                        && vo.getSubmitterId() == USER_B),
                "别的部门提交的单不得出现在列表里：" + listed.getRecords());
    }

}
