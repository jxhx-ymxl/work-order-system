package com.workorder.agent;

import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.DeptComparisonTool;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * **强固定流程基线**的判据（`docs/agent-design/AGENT-LEARNING-EVAL.md` §3.1）。
 *
 * <p>基线是"代码决定下一步"的条件工作流：透明关键词做有限任务分类 → 固定顺序取证
 * （起点单事实 → 超时/转手时加同部门对照）→ **同一校验器** → 同一模板渲染。
 * 它必须与 agent **同工具、同 ToolContext、同证据登记、同校验、同上限**——否则 S6 的成对比较失去意义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("强固定流程基线")
class FixedFlowInvestigatorTest {

    private static final String ORDER_NO = "WO-20260607-00001";
    private static final long SUBMITTER_ID = 100L;
    private static final long ASSIGNEE_ID = 200L;
    private static final long DEPT = 7L;
    private static final long OTHER_DEPT = 9L;

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderLogMapper workOrderLogMapper;
    @Mock
    private UserMapper userMapper;

    private StubModelServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private static ToolContext ctxOf(long deptId) {
        return ToolContext.ofDepartment("inv-fixed-1", "42", String.valueOf(deptId));
    }

    private AgentToolRegistry registry() {
        return new AgentToolRegistry(List.of(
                new OrderFactsTool(workOrderMapper, workOrderLogMapper, userMapper),
                new DeptComparisonTool(workOrderMapper, userMapper)));
    }

    private FixedFlowInvestigator baseline() {
        return new FixedFlowInvestigator(registry(), AgentLimits.s1Defaults());
    }

    private static WorkOrder order(String status, Long assigneeId, LocalDateTime sla, Long submitterId) {
        WorkOrder order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setStatus(status);
        order.setSubmitterId(submitterId);
        order.setAssigneeId(assigneeId);
        order.setSlaDeadline(sla);
        order.setCreatedAt(LocalDateTime.of(2026, 10, 6, 9, 0));
        return order;
    }

    private static User user(long id, String username, Long deptId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDeptId(deptId);
        return user;
    }

    private static WorkOrderLog log(String action, long operatorId) {
        WorkOrderLog log = new WorkOrderLog();
        log.setId(1L);
        log.setOrderId(1L);
        log.setOrderNo(ORDER_NO);
        log.setAction(action);
        log.setOperatorId(operatorId);
        log.setOldStatus("PENDING");
        log.setNewStatus("ACCEPTED");
        log.setCreatedAt(LocalDateTime.of(2026, 10, 6, 9, 30));
        return log;
    }

    /** 部门内、有处理人、有 SLA、有接单记录的"正常"数据。 */
    private void stubInDepartmentHappyPath() {
        lenient().when(workOrderMapper.selectOne(any())).thenReturn(
                order("IN_PROGRESS", ASSIGNEE_ID, LocalDateTime.of(2026, 10, 7, 12, 0), SUBMITTER_ID));
        lenient().when(userMapper.selectList(any())).thenReturn(
                List.of(user(SUBMITTER_ID, "zhangsan", DEPT), user(ASSIGNEE_ID, "admin", DEPT)));
        lenient().when(userMapper.selectById(ASSIGNEE_ID)).thenReturn(user(ASSIGNEE_ID, "admin", DEPT));
        lenient().when(workOrderLogMapper.selectList(any())).thenReturn(List.of(log("ACCEPT", ASSIGNEE_ID)));
        lenient().when(workOrderMapper.selectList(any())).thenReturn(List.of());
    }

    /** 同上，但工单处于**待验收**（`AWAIT_APPROVAL`）：下一步在提交人侧。 */
    private void stubAwaitApproval() {
        lenient().when(workOrderMapper.selectOne(any())).thenReturn(
                order("AWAIT_APPROVAL", ASSIGNEE_ID, LocalDateTime.of(2026, 10, 7, 12, 0), SUBMITTER_ID));
        lenient().when(userMapper.selectList(any())).thenReturn(
                List.of(user(SUBMITTER_ID, "zhangsan", DEPT), user(ASSIGNEE_ID, "admin", DEPT)));
        lenient().when(userMapper.selectById(ASSIGNEE_ID)).thenReturn(user(ASSIGNEE_ID, "admin", DEPT));
        lenient().when(workOrderLogMapper.selectList(any())).thenReturn(List.of(log("COMPLETE", ASSIGNEE_ID)));
        lenient().when(workOrderMapper.selectList(any())).thenReturn(List.of());
    }

    /**
     * 固定流程也要能走到新增的目录项（否则业务默认模式 `mode=fixed` 永远用不上它）。
     *
     * <p>触发条件只看**证据里的状态事实** `order.status = AWAIT_APPROVAL`；方向由状态机给出
     * （验收通过 / 驳回只允许提交人做），不是从任何评测期望里抄来的。
     */
    @Test
    @DisplayName("待验收（AWAIT_APPROVAL）：建议指向提交人侧，而不是「联系处理人」")
    void awaitApprovalSuggestsSubmitterSide() {
        stubAwaitApproval();

        AgentRunResult result = baseline().investigate(ctxOf(DEPT), ORDER_NO, "工单 " + ORDER_NO + " 现在到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(List.of(AgentSuggestion.WAIT_FOR_SUBMITTER_ACCEPTANCE.name()),
                result.report().suggestionIds(),
                "待验收时处理人已经交完，方向应落在提交人侧");
        String rendered = new AgentReportRenderer().render(result.report(), result.evidence());
        assertTrue(rendered.contains("等待提交人验收，必要时提醒其处理"),
                "渲染出的应是目录里的固定文案：" + rendered);
    }

    // ---------- 规则分类四条 ----------

    @Test
    @DisplayName("规则分类：到哪一步 → ORDER_STATUS")
    void classifiesOrderStatus() {
        stubInDepartmentHappyPath();
        AgentRunResult result = baseline().investigate(ctxOf(DEPT), ORDER_NO, "工单 " + ORDER_NO + " 现在到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(AgentProblemType.ORDER_STATUS, result.report().problemType());
    }

    @Test
    @DisplayName("规则分类：为什么没处理完 / 超时 → TIMEOUT_SITUATION")
    void classifiesTimeoutSituation() {
        stubInDepartmentHappyPath();
        AgentRunResult result = baseline().investigate(ctxOf(DEPT), ORDER_NO, "工单 " + ORDER_NO + " 为什么没处理完？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(AgentProblemType.TIMEOUT_SITUATION, result.report().problemType());
    }

    @Test
    @DisplayName("规则分类：谁处理过 / 转过几手 → REASSIGN_HISTORY")
    void classifiesReassignHistory() {
        stubInDepartmentHappyPath();
        AgentRunResult result = baseline().investigate(ctxOf(DEPT), ORDER_NO, "工单 " + ORDER_NO + " 被谁处理过？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(AgentProblemType.REASSIGN_HISTORY, result.report().problemType());
    }

    @Test
    @DisplayName("规则分类：都不匹配 → UNSUPPORTED（且不带证据与建议）")
    void classifiesUnsupported() {
        AgentRunResult result = baseline().investigate(ctxOf(DEPT), ORDER_NO, "食堂几点开门？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(AgentProblemType.UNSUPPORTED, result.report().problemType());
        assertTrue(result.report().evidenceIds().isEmpty());
        assertTrue(result.report().suggestionIds().isEmpty());
    }

    // ---------- 不弱化 / 可比性 / 上限统一 ----------

    @Test
    @DisplayName("不弱化：跨部门调用者同样 FORBIDDEN、facts 为空（复用工具授权，不自写一套）")
    void crossDepartmentIsForbidden() {
        lenient().when(workOrderMapper.selectOne(any())).thenReturn(
                order("IN_PROGRESS", ASSIGNEE_ID, LocalDateTime.of(2026, 10, 7, 12, 0), SUBMITTER_ID));
        lenient().when(userMapper.selectList(any())).thenReturn(List.of(user(999L, "other", OTHER_DEPT)));

        AgentRunResult result = baseline().investigate(ctxOf(OTHER_DEPT), ORDER_NO, "工单 " + ORDER_NO + " 现在到哪一步了？");

        assertEquals(AgentStatus.FAILED, result.status());
        assertEquals("FORBIDDEN", result.failure().code());
        assertNull(result.report(), "失败不得产出报告");
        assertTrue(result.evidence().isEmpty(), "拒绝时不得留下任何事实证据");
    }

    @Test
    @DisplayName("可比性：同一输入同一数据下，基线与 agent 都产出 AgentReport，且都能过同一个渲染器")
    void producesComparableReportWithAgent() {
        stubInDepartmentHappyPath();
        String question = "工单 " + ORDER_NO + " 现在到哪一步了？";

        AgentRunResult fixed = baseline().investigate(ctxOf(DEPT), ORDER_NO, question);

        // agent 侧：同一工具集 + 同一数据（桩模型驱动）
        AgentToolRegistry registry = registry();
        stub = new StubModelServer(
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + ORDER_NO + "\"}")),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("CONTACT_ASSIGNEE"))));
        AgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
        AgentRunResult agent = new InvestigationAgent(model, registry, AgentLimits.s1Defaults())
                .investigate(ctxOf(DEPT), ORDER_NO, question);

        assertEquals(AgentStatus.COMPLETED, fixed.status(), () -> "baseline failure=" + fixed.failure());
        assertEquals(AgentStatus.COMPLETED, agent.status(), () -> "agent failure=" + agent.failure());
        assertNotNull(fixed.report());
        assertNotNull(agent.report());
        assertFalse(fixed.report().evidenceIds().isEmpty());
        assertFalse(agent.report().evidenceIds().isEmpty());

        AgentReportRenderer renderer = new AgentReportRenderer();
        String fixedText = renderer.render(fixed.report(), fixed.evidence());
        String agentText = renderer.render(agent.report(), agent.evidence());
        for (String section : List.of("【已核实事实】", "【证据缺口】", "【下一步核实建议】")) {
            assertTrue(fixedText.contains(section), "基线报告可渲染：" + fixedText);
            assertTrue(agentText.contains(section), "agent 报告可渲染：" + agentText);
        }
    }

    @Test
    @DisplayName("上限统一：基线同样计入工具调用次数，超限给出与 agent 同名的 TOOL_BUDGET_EXCEEDED")
    void sharesTheSameToolBudget() {
        stubInDepartmentHappyPath();
        FixedFlowInvestigator tight = new FixedFlowInvestigator(registry(),
                AgentLimits.s1Defaults().withMaxToolCalls(1));   // 超时类需要 2 次工具（起点单 + 对照）

        AgentRunResult result = tight.investigate(ctxOf(DEPT), ORDER_NO, "工单 " + ORDER_NO + " 为什么没处理完？");

        assertEquals(AgentStatus.FAILED, result.status());
        assertEquals("TOOL_BUDGET_EXCEEDED", result.failure().code(), "与 agent 同名原因码");
        assertNull(result.report(), "超限不得产出报告");
    }

}
