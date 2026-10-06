package com.workorder.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用前的权限重校验（设计稿 L116 第 3 条）的判据：真改库撤销 -> CANCELLED(PERMISSION_REVOKED)；
 * 没变 -> 仍 COMPLETED（防误报）；撤权后不切换到新范围（不得再有新查询）；agent 与基线共用同一个协作者。
 * 模型一律用桩，零真实费用。
 */
@SpringBootTest(properties = "agent.investigation.enabled=true")
@ActiveProfiles("test")
@Transactional
@DisplayName("工具调用前权限重校验（PERMISSION_REVOKED）")
class PermissionRecheckTest {

    private static final long DEPT_A = 7301L;
    private static final long DEPT_B = 7302L;
    private static final long ADMIN_ID = 8301L;
    private static final long SUBMITTER_ID = 8302L;
    private static final long ASSIGNEE_ID = 8303L;
    private static final long DEPT_ADMIN_ROLE_ID = 4L;
    private static final long HANDLER_ROLE_ID = 3L;
    private static final long SUBMITTER_ROLE_ID = 2L;

    private static final StubModelServer MODEL = new StubModelServer();
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(93000);

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

    /** 取完事实后执行副作用（撤角色 / 改部门），并记录该工具被执行了几次。 */
    private static AgentTool countedAndMutating(AgentTool real, AtomicInteger counter, Runnable after) {
        return new AgentTool() {
            @Override public String name() { return real.name(); }
            @Override public String description() { return real.description(); }
            @Override public Map<String, Object> parameterSchema() { return real.parameterSchema(); }
            @Override public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
                counter.incrementAndGet();
                ToolOutcome outcome = real.execute(ctx, arguments);
                if (after != null) {
                    after.run();
                }
                return outcome;
            }
        };
    }

    private record Rig(AgentToolRegistry registry, AtomicInteger rootCalls, AtomicInteger peerCalls) {
    }

    private Rig rig(Runnable afterFacts, Runnable afterPeer) {
        AtomicInteger rootCalls = new AtomicInteger();
        AtomicInteger peerCalls = new AtomicInteger();
        AgentTool root = countedAndMutating(
                new OrderFactsTool(workOrderMapper, workOrderLogMapper, userMapper), rootCalls, afterFacts);
        AgentTool peer = countedAndMutating(
                new DeptComparisonTool(workOrderMapper, userMapper), peerCalls, afterPeer);
        return new Rig(new AgentToolRegistry(List.of(root, peer)), rootCalls, peerCalls);
    }

    private AgentInvestigationService service(AgentToolRegistry registry, String mode, InvestigationAgent agent) {
        PermissionRecheck recheck = new PermissionRecheck(workOrderService);
        FixedFlowInvestigator baseline = new FixedFlowInvestigator(registry, limits,
                new FinalReview(registry, workOrderMapper, userMapper), recheck);
        return new AgentInvestigationService(agent, baseline, renderer, mode, workOrderService);
    }

    private WorkOrder fixture() {
        insertUser(ADMIN_ID, "recheck-admin", DEPT_A, DEPT_ADMIN_ROLE_ID);
        insertUser(SUBMITTER_ID, "recheck-submitter", DEPT_A, SUBMITTER_ROLE_ID);
        insertUser(ASSIGNEE_ID, "recheck-handler", DEPT_A, HANDLER_ROLE_ID);

        WorkOrder order = new WorkOrder();
        order.setOrderNo(String.format("WO-20261006-%05d", SEQ.incrementAndGet()));
        order.setTitle("撤权测试工单");
        order.setContent("权限重校验");
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus("IN_PROGRESS");
        order.setSubmitterId(SUBMITTER_ID);
        order.setAssigneeId(ASSIGNEE_ID);
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

    private void revokeDeptAdminRole() {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, ADMIN_ID).eq(UserRole::getRoleId, DEPT_ADMIN_ROLE_ID));
    }

    private void moveToOtherDepartment() {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, ADMIN_ID).set(User::getDeptId, DEPT_B));
    }

    @Test
    @DisplayName("真改库：工具取证之后撤掉 DEPT_ADMIN 角色 → CANCELLED(PERMISSION_REVOKED)、无报告、渲染带原因码")
    void revokedRoleEndsCancelled() {
        WorkOrder order = fixture();
        Rig rig = rig(this::revokeDeptAdminRole, null);

        AgentInvestigationService.Outcome outcome = service(rig.registry(), AgentInvestigationService.MODE_FIXED, null)
                .investigate(ADMIN_ID, order.getOrderNo(), "工单 " + order.getOrderNo() + " 被谁处理过？");

        assertEquals("CANCELLED", outcome.status());
        assertEquals("PERMISSION_REVOKED", outcome.failureCode());
        assertNull(outcome.report(), "取消不得产出报告");
        assertNotNull(outcome.renderedText());
        assertTrue(outcome.renderedText().contains("调查已取消"), outcome.renderedText());
        assertTrue(outcome.renderedText().contains("PERMISSION_REVOKED"), outcome.renderedText());
    }

    @Test
    @DisplayName("没改任何权限/部门 → 仍然 COMPLETED（防误报）")
    void unchangedStillCompletes() {
        WorkOrder order = fixture();
        Rig rig = rig(null, null);

        AgentInvestigationService.Outcome outcome = service(rig.registry(), AgentInvestigationService.MODE_FIXED, null)
                .investigate(ADMIN_ID, order.getOrderNo(), "工单 " + order.getOrderNo() + " 被谁处理过？");

        assertEquals("COMPLETED", outcome.status(), () -> "failure=" + outcome.failureCode());
        assertNotNull(outcome.report());
    }

    @Test
    @DisplayName("不切换范围：改部门后终止，不得用新部门再发起任何工具查询")
    void doesNotContinueWithTheNewDepartment() {
        WorkOrder order = fixture();
        Rig rig = rig(this::moveToOtherDepartment, null);

        AgentInvestigationService.Outcome outcome = service(rig.registry(), AgentInvestigationService.MODE_FIXED, null)
                .investigate(ADMIN_ID, order.getOrderNo(), "工单 " + order.getOrderNo() + " 被谁处理过？");

        assertEquals("CANCELLED", outcome.status());
        assertEquals("PERMISSION_REVOKED", outcome.failureCode());
        assertEquals(1, rig.rootCalls().get(), "主单只被读过一次");
        assertEquals(0, rig.peerCalls().get(), "撤权后不得再发起任何工具查询（尤其不得按新部门继续查）");
    }

    @Test
    @DisplayName("agent 路径用的是同一个重校验协作者：撤权后同样 CANCELLED")
    void agentPathUsesTheSameRecheck() {
        WorkOrder order = fixture();
        Rig rig = rig(this::revokeDeptAdminRole, null);
        MODEL.enqueue(StubModelServer.json(StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + order.getOrderNo() + "\"}")),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("CONTACT_ASSIGNEE"))));
        HttpAgentModel model = new HttpAgentModel(MODEL.url(), "stub-key", "stub-model", rig.registry().definitions(),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
        InvestigationAgent agent = new InvestigationAgent(model, rig.registry(), limits, OrderFactsTool.NAME,
                new FinalReview(rig.registry(), workOrderMapper, userMapper),
                new PermissionRecheck(workOrderService));

        AgentInvestigationService.Outcome outcome =
                service(rig.registry(), AgentInvestigationService.MODE_AGENT, agent)
                        .investigate(ADMIN_ID, order.getOrderNo(), "工单 " + order.getOrderNo() + " 现在到哪一步了？");

        assertEquals("CANCELLED", outcome.status(), () -> "failure=" + outcome.failureCode());
        assertEquals("PERMISSION_REVOKED", outcome.failureCode());
        assertNull(outcome.report());
    }
}
