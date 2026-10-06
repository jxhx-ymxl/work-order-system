package com.workorder.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.DeptComparisonTool;
import com.workorder.agent.tool.OrderFactsTool;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终短读取复核（设计稿 L213）的判据：真改业务字段 → INCOMPLETE(STATE_CHANGED)；没改 → 仍 COMPLETED；
 * 只改 version → 不算变化；对照单被移出可见范围 → 也算变化；另加一条：agent 与基线走同一个协作者。
 * 模型一律用桩，零真实费用。
 */
@SpringBootTest(properties = "agent.investigation.enabled=true")
@ActiveProfiles("test")
@Transactional
@DisplayName("最终短读取复核（STATE_CHANGED）")
class FinalReviewTest {

    private static final long DEPT_A = 7201L;
    private static final long DEPT_B = 7202L;
    private static final long ADMIN_ID = 8201L;
    private static final long SUBMITTER_ID = 8202L;
    private static final long ASSIGNEE_ID = 8203L;
    private static final long OTHER_DEPT_USER_ID = 8204L;
    private static final long DEPT_ADMIN_ROLE_ID = 4L;
    private static final long HANDLER_ROLE_ID = 3L;
    private static final long SUBMITTER_ROLE_ID = 2L;

    private static final StubModelServer MODEL = new StubModelServer();
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(92000);

    @AfterAll
    static void closeModel() {
        MODEL.close();
    }

    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private AgentLimits limits;
    @Autowired private AgentReportRenderer renderer;
    @Autowired private WorkOrderService workOrderService;

    /** 装饰器：先取事实、再改库——精确复现"工具取证之后、返回之前数据变了"。 */
    private static AgentTool mutating(AgentTool real, Runnable afterRead) {
        return new AgentTool() {
            @Override public String name() { return real.name(); }
            @Override public String description() { return real.description(); }
            @Override public Map<String, Object> parameterSchema() { return real.parameterSchema(); }
            @Override public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
                ToolOutcome outcome = real.execute(ctx, arguments);
                afterRead.run();
                return outcome;
            }
        };
    }

    private AgentToolRegistry registry(Runnable afterFacts, Runnable afterPeer) {
        AgentTool root = afterFacts == null
                ? new OrderFactsTool(workOrderMapper, workOrderLogMapper, userMapper)
                : mutating(new OrderFactsTool(workOrderMapper, workOrderLogMapper, userMapper), afterFacts);
        AgentTool peer = afterPeer == null
                ? new DeptComparisonTool(workOrderMapper, userMapper)
                : mutating(new DeptComparisonTool(workOrderMapper, userMapper), afterPeer);
        return new AgentToolRegistry(List.of(root, peer));
    }

    private FixedFlowInvestigator baseline(AgentToolRegistry registry) {
        return new FixedFlowInvestigator(registry, limits,
                new FinalReview(registry, workOrderMapper, userMapper));
    }

    private AgentInvestigationService service(AgentToolRegistry registry, String mode, InvestigationAgent agent) {
        return new AgentInvestigationService(agent, baseline(registry), renderer, mode, workOrderService);
    }

    private AgentInvestigationService.Outcome runFixed(AgentToolRegistry registry, String orderNo, String question) {
        return service(registry, AgentInvestigationService.MODE_FIXED, null)
                .investigate(ADMIN_ID, orderNo, question);
    }

    private WorkOrder fixture() {
        insertUser(ADMIN_ID, "review-admin", DEPT_A, DEPT_ADMIN_ROLE_ID);
        insertUser(SUBMITTER_ID, "review-submitter", DEPT_A, SUBMITTER_ROLE_ID);
        insertUser(ASSIGNEE_ID, "review-handler", DEPT_A, HANDLER_ROLE_ID);
        insertUser(OTHER_DEPT_USER_ID, "review-other", DEPT_B, SUBMITTER_ROLE_ID);
        return insertOrder(SUBMITTER_ID, ASSIGNEE_ID, "IN_PROGRESS");
    }

    private WorkOrder insertOrder(long submitterId, Long assigneeId, String status) {
        WorkOrder order = new WorkOrder();
        order.setOrderNo(String.format("WO-20261006-%05d", SEQ.incrementAndGet()));
        order.setTitle("复核测试工单");
        order.setContent("最终短读取复核");
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus(status);
        order.setSubmitterId(submitterId);
        order.setAssigneeId(assigneeId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setTriageStatus("DONE");
        order.setVersion(0);
        order.setSlaDeadline(LocalDateTime.now().plusDays(1));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(order);

        WorkOrderLog log = new WorkOrderLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setOperatorId(ASSIGNEE_ID);
        log.setAction("ACCEPT");
        log.setNewStatus("IN_PROGRESS");
        log.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(log);
        return order;
    }

    private void insertUser(long id, String username, long deptId, long roleId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe");
        user.setDeptId(deptId);
        user.setStatus(1);
        userMapper.insert(user);
        UserRole binding = new UserRole();
        binding.setUserId(id);
        binding.setRoleId(roleId);
        userRoleMapper.insert(binding);
    }

    private void changeStatus(String orderNo, String status) {
        workOrderMapper.update(null, new LambdaUpdateWrapper<WorkOrder>()
                .eq(WorkOrder::getOrderNo, orderNo).set(WorkOrder::getStatus, status));
    }

    @Test
    @DisplayName("真改库：工具取证之后改了 status → INCOMPLETE(STATE_CHANGED)、无报告、渲染标未完成")
    void changedBusinessFieldEndsIncomplete() {
        WorkOrder order = fixture();
        AgentToolRegistry registry = registry(() -> changeStatus(order.getOrderNo(), "CLOSED"), null);

        AgentInvestigationService.Outcome outcome =
                runFixed(registry, order.getOrderNo(), "工单 " + order.getOrderNo() + " 现在到哪一步了？");

        assertEquals("INCOMPLETE", outcome.status());
        assertEquals("STATE_CHANGED", outcome.failureCode());
        assertNull(outcome.report(), "未完成不得产出报告");
        assertNotNull(outcome.renderedText(), "未完成要有对外文本（部分事实 + 未完成）");
        assertTrue(outcome.renderedText().contains("调查未完成"), outcome.renderedText());
        assertTrue(outcome.renderedText().contains("STATE_CHANGED"), outcome.renderedText());
        assertFalse(outcome.renderedText().contains("【下一步核实建议】"),
                "未完成不是正常报告：不得出现正常报告的段落结构");
    }

    @Test
    @DisplayName("没改任何业务字段 → 仍然 COMPLETED（防恒定的误报）")
    void unchangedStillCompletes() {
        WorkOrder order = fixture();

        AgentInvestigationService.Outcome outcome =
                runFixed(registry(null, null), order.getOrderNo(), "工单 " + order.getOrderNo() + " 现在到哪一步了？");

        assertEquals("COMPLETED", outcome.status(), () -> "failure=" + outcome.failureCode());
        assertNotNull(outcome.report());
    }

    @Test
    @DisplayName("只改 version、不改任何业务字段 → 不算变化（version 是并发控制字段，不是业务事实）")
    void versionOnlyChangeIsNotABusinessChange() {
        WorkOrder order = fixture();
        AgentToolRegistry registry = registry(() -> workOrderMapper.update(null,
                new LambdaUpdateWrapper<WorkOrder>()
                        .eq(WorkOrder::getOrderNo, order.getOrderNo())
                        .set(WorkOrder::getVersion, 99)), null);

        AgentInvestigationService.Outcome outcome =
                runFixed(registry, order.getOrderNo(), "工单 " + order.getOrderNo() + " 现在到哪一步了？");

        assertEquals("COMPLETED", outcome.status(),
                "设计稿：version 单独不够（markTriageFailed 不递增 version）——判据是业务字段逐条比对；failure="
                        + outcome.failureCode());
    }

    @Test
    @DisplayName("被引用的对照单被移出可见范围 → 同样判 INCOMPLETE(STATE_CHANGED)")
    void peerLeavingScopeEndsIncomplete() {
        WorkOrder main = fixture();
        WorkOrder peer = insertOrder(SUBMITTER_ID, ASSIGNEE_ID, "ACCEPTED");

        // 对照工具取完事实后，把对照单的提交人挪到别的部门 → 复核重查时应发现它已不在范围内
        AgentToolRegistry registry = registry(null, () -> workOrderMapper.update(null,
                new LambdaUpdateWrapper<WorkOrder>()
                        .eq(WorkOrder::getOrderNo, peer.getOrderNo())
                        .set(WorkOrder::getSubmitterId, OTHER_DEPT_USER_ID)));

        AgentInvestigationService.Outcome outcome =
                runFixed(registry, main.getOrderNo(), "工单 " + main.getOrderNo() + " 被谁处理过？");

        assertEquals("INCOMPLETE", outcome.status());
        assertEquals("STATE_CHANGED", outcome.failureCode());
        assertNull(outcome.report());
    }

    @Test
    @DisplayName("agent 路径走的是同一个复核协作者：改了业务字段一样 INCOMPLETE")
    void agentPathUsesTheSameReviewer() {
        WorkOrder order = fixture();
        AgentToolRegistry registry = registry(() -> changeStatus(order.getOrderNo(), "CLOSED"), null);
        MODEL.enqueue(StubModelServer.json(StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + order.getOrderNo() + "\"}")),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("CONTACT_ASSIGNEE"))));
        HttpAgentModel model = new HttpAgentModel(MODEL.url(), "stub-key", "stub-model", registry.definitions(),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
        InvestigationAgent agent = new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME,
                new FinalReview(registry, workOrderMapper, userMapper));

        AgentInvestigationService.Outcome outcome = service(registry, AgentInvestigationService.MODE_AGENT, agent)
                .investigate(ADMIN_ID, order.getOrderNo(), "工单 " + order.getOrderNo() + " 现在到哪一步了？");

        assertEquals("INCOMPLETE", outcome.status(), () -> "failure=" + outcome.failureCode());
        assertEquals("STATE_CHANGED", outcome.failureCode());
        assertNull(outcome.report());
    }
}
