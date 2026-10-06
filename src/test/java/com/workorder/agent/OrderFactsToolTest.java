package com.workorder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.agent.support.StubModelServer;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 第一个真实工具（{@link OrderFactsTool}）的纵向切片判据。
 *
 * <p>四类边界（对应 §1 第二题 / 第四题与 §3.1）：跨部门拒绝 / 升级单仍受部门限制 /
 * 待分配池不构成越权入口（多分支不扩权）/ 证据不足显式标未知（不编造日期或次数）。
 * 另加一条**权限内正例**——没有它，"一律拒绝"也能过前三条（测试自身的退化形态）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("第一个真实工具：事实 + 部门范围")
class OrderFactsToolTest {

    private static final String ORDER_NO = "WO-20260607-00001";
    private static final long SUBMITTER_ID = 100L;
    private static final long ASSIGNEE_ID = 200L;
    private static final long CALLER_DEPT = 7L;      // 提交人所在部门
    private static final long OTHER_DEPT = 9L;       // 跨部门场景用的调用者部门

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderLogMapper workOrderLogMapper;
    @Mock
    private UserMapper userMapper;

    private StubModelServer stub;

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private OrderFactsTool tool() {
        return new OrderFactsTool(workOrderMapper, workOrderLogMapper, userMapper);
    }

    private static ToolContext ctxOf(long deptId) {
        return ToolContext.ofDepartment("inv-real-1", "42", String.valueOf(deptId));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode args() {
        return JSON.createObjectNode().put("orderNo", ORDER_NO);
    }

    private static WorkOrder order(String status, Long assigneeId, LocalDateTime slaDeadline) {
        WorkOrder order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setStatus(status);
        order.setSubmitterId(SUBMITTER_ID);
        order.setAssigneeId(assigneeId);
        order.setSlaDeadline(slaDeadline);
        return order;
    }

    private static User user(long id, String username, Long deptId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDeptId(deptId);
        return user;
    }

    private static WorkOrderLog log(String action, long operatorId, LocalDateTime at) {
        WorkOrderLog log = new WorkOrderLog();
        log.setId(1L);
        log.setOrderId(1L);
        log.setOrderNo(ORDER_NO);
        log.setAction(action);
        log.setOperatorId(operatorId);
        log.setOldStatus("PENDING");
        log.setNewStatus("ACCEPTED");
        log.setCreatedAt(at);
        return log;
    }

    private void stubOrderWithDept(WorkOrder order, long deptIdOfMembers) {
        when(workOrderMapper.selectOne(any())).thenReturn(order);
        when(userMapper.selectList(any())).thenReturn(
                List.of(user(SUBMITTER_ID, "zhangsan", deptIdOfMembers)));
    }

    /**
     * 跨部门场景的夹具：**调用者所在部门**只有别的成员（id=999），提交人不在其中。
     *
     * <p>注意不能复用 {@link #stubOrderWithDept}——Mockito 的 `any()` 会忽略 wrapper，
     * 无论调用者部门是几都返回同一批成员，测试会失去区分力（本轮实测踩到：三条边界用例因此假绿/假红）。
     */
    private void stubOrderInOtherDepartment(WorkOrder order) {
        when(workOrderMapper.selectOne(any())).thenReturn(order);
        when(userMapper.selectList(any())).thenReturn(
                List.of(user(999L, "other", OTHER_DEPT)));
    }

    @Test
    @DisplayName("部门范围内：返回五条事实；RELEASE 行渲染成系统操作，告警次数显式未知")
    void sameDepartment_returnsFacts() {
        LocalDateTime sla = LocalDateTime.of(2026, 10, 7, 12, 0);
        stubOrderWithDept(order("IN_PROGRESS", ASSIGNEE_ID, sla), CALLER_DEPT);
        when(userMapper.selectById(ASSIGNEE_ID)).thenReturn(user(ASSIGNEE_ID, "admin", CALLER_DEPT));
        when(workOrderLogMapper.selectList(any())).thenReturn(List.of(
                log("ACCEPT", ASSIGNEE_ID, LocalDateTime.of(2026, 10, 6, 9, 0)),
                log("RELEASE", 0L, LocalDateTime.of(2026, 10, 6, 10, 0))));

        ToolOutcome outcome = tool().execute(ctxOf(CALLER_DEPT), args());

        assertTrue(outcome.ok(), outcome.errorMessage());
        Map<String, String> facts = outcome.facts();
        assertEquals("true", facts.get("order.exists"));
        assertEquals("IN_PROGRESS", facts.get("order.status"));
        assertEquals("a*", facts.get("order.assignee"), "处理人只给脱敏显示名（§4.4）");
        assertEquals("2026-10-07 12:00", facts.get("order.sla_deadline"));
        assertTrue(facts.get("order.accept_events").contains("ACCEPT@2026-10-06 09:00 by a*"),
                facts.get("order.accept_events"));
        assertTrue(facts.get("order.accept_events").contains("RELEASE@2026-10-06 10:00 by 系统操作"),
                "operatorId=0 必须渲染成系统操作，不得挂在某个人名下：" + facts.get("order.accept_events"));
        assertTrue(outcome.unknownFacts().contains("order.alert_count"), "告警次数没有记录源，只能显式未知");
        assertFalse(facts.get("order.alert_count").contains("次"), "不得编造告警次数：" + facts.get("order.alert_count"));
    }

    @Test
    @DisplayName("跨部门拒绝：事实一条都不返回（业务错误，不是空事实）")
    void crossDepartment_isRejected() {
        stubOrderInOtherDepartment(order("IN_PROGRESS", ASSIGNEE_ID, LocalDateTime.now()));

        ToolOutcome outcome = tool().execute(ctxOf(OTHER_DEPT), args());

        assertFalse(outcome.ok());
        assertEquals("FORBIDDEN", outcome.errorCode());
        assertTrue(outcome.facts().isEmpty(), "拒绝时不得夹带任何事实");
    }

    @Test
    @DisplayName("升级单仍受部门限制：ESCALATED_ADMIN 不扩大可见范围（§1 第二题）")
    void escalatedOrder_stillDepartmentScoped() {
        stubOrderInOtherDepartment(order("ESCALATED_ADMIN", null, LocalDateTime.now()));

        ToolOutcome outcome = tool().execute(ctxOf(OTHER_DEPT), args());

        assertFalse(outcome.ok(), "升级单不得因为状态而放宽部门范围");
        assertEquals("FORBIDDEN", outcome.errorCode());
    }

    @Test
    @DisplayName("待分配池不构成越权入口：PENDING + 未分配 的跨部门工单仍被拒（不复制 detail 的处理人分支）")
    void pendingPool_doesNotWidenScope() {
        stubOrderInOtherDepartment(order("PENDING", null, LocalDateTime.now()));

        ToolOutcome outcome = tool().execute(ctxOf(OTHER_DEPT), args());

        assertFalse(outcome.ok(), "『待分配池人人可看』是 detail 的口径，本工具不得复用");
        assertEquals("FORBIDDEN", outcome.errorCode());
    }

    @Test
    @DisplayName("证据不足不变成虚构结论：SLA 为 NULL / 无接单记录 → 显式未知 + 空事实标记，不编造")
    void missingEvidence_isMarkedUnknownNotFabricated() {
        stubOrderWithDept(order("PENDING", null, null), CALLER_DEPT);
        when(workOrderLogMapper.selectList(any())).thenReturn(List.of());

        ToolOutcome outcome = tool().execute(ctxOf(CALLER_DEPT), args());

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertEquals("无 SLA 截止（NULL 未登记）", outcome.facts().get("order.sla_deadline"));
        // 业务依据（D82 / 映射表 §3 空值语义）：NULL = "无 SLA" 是**已知事实**，只给值、不标未知。
        // 标未知会让该事实在 TIMEOUT_SITUATION 下撞 "该类型不允许未知" 分支 → 永远判未完成。
        assertFalse(outcome.unknownFacts().contains("order.sla_deadline"),
                "NULL 是已知的『无 SLA』，不是未知");
        assertEquals("未分配", outcome.facts().get("order.assignee"));
        // 业务依据（D83）：空值 = 已知事实（assignee_id=NULL 就是"未分配"），不标未知。
        // 标未知会让"为什么没人接"引用该证据时撞 allowedUnknown → 该类型永远判未完成。
        assertFalse(outcome.unknownFacts().contains("order.assignee"),
                "\"未分配\"是已知事实，不是未知");
        assertTrue(outcome.emptyFacts().contains("order.accept_events"),
                "0 行是『从未接单或指派』这条**完整事实**（不是未知）");
    }

    @Test
    @DisplayName("纵向链路走通：真实工具经调查循环产出的证据编号可支撑报告（部门范围内完成）")
    void realTool_wiresThroughTheLoop() {
        LocalDateTime sla = LocalDateTime.of(2026, 10, 7, 12, 0);
        when(workOrderMapper.selectOne(any())).thenReturn(order("IN_PROGRESS", ASSIGNEE_ID, sla));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", CALLER_DEPT)));
        when(userMapper.selectById(ASSIGNEE_ID)).thenReturn(user(ASSIGNEE_ID, "admin", CALLER_DEPT));
        when(workOrderLogMapper.selectList(any())).thenReturn(List.of(
                log("ACCEPT", ASSIGNEE_ID, LocalDateTime.of(2026, 10, 6, 9, 0))));

        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool()));
        stub = new StubModelServer(
                StubModelServer.json(StubModelServer.toolCallTurn("call_real", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + ORDER_NO + "\"}")),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("CONTACT_ASSIGNEE"))));
        AgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
        InvestigationAgent agent = new InvestigationAgent(model, registry, AgentLimits.s1Defaults());

        AgentRunResult result = agent.investigate(ctxOf(CALLER_DEPT), "这张单现在到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertNotNull(result.report());
        assertEquals(1, result.toolCalls());
        String toolMessage = StubModelServer.toolMessages(stub.received(1)).get(0).path("content").asText();
        assertTrue(toolMessage.contains("order.status"), toolMessage);
        assertTrue(toolMessage.contains("order.accept_events"), toolMessage);
    }

    @Test
    @DisplayName("无 SLA 的单：sla_deadline 是**已知事实**（只给值、不标未知）→ TIMEOUT_SITUATION 能完成")
    void nullSlaIsKnownFact_timeoutSituationCompletes() {
        when(workOrderMapper.selectOne(any())).thenReturn(order("IN_PROGRESS", ASSIGNEE_ID, null));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", CALLER_DEPT)));
        when(userMapper.selectById(ASSIGNEE_ID)).thenReturn(user(ASSIGNEE_ID, "admin", CALLER_DEPT));
        when(workOrderLogMapper.selectList(any())).thenReturn(List.of());

        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool()));
        stub = new StubModelServer(
                StubModelServer.json(StubModelServer.toolCallTurn("call_real", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + ORDER_NO + "\"}")),
                StubModelServer.json(StubModelServer.finishTurn("TIMEOUT_SITUATION",
                        List.of("E1", "E2", "E4"), List.of())));
        AgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
        InvestigationAgent agent = new InvestigationAgent(model, registry, AgentLimits.s1Defaults());

        AgentRunResult result = agent.investigate(ctxOf(CALLER_DEPT), "这单超时了吗？");

        assertEquals(AgentStatus.COMPLETED, result.status(),
                () -> "无 SLA 是已知事实（NULL = 无 SLA），不得因标未知而让该类型永远判未完成；failure=" + result.failure());
    }

    @Test
    @DisplayName("未分配的单：assignee 是**已知事实**（值\"未分配\"）→ TIMEOUT_SITUATION 引用该证据也能完成")
    void unassignedIsKnownFact_timeoutSituationCompletes() {
        LocalDateTime sla = LocalDateTime.of(2026, 10, 7, 12, 0);
        when(workOrderMapper.selectOne(any())).thenReturn(order("PENDING", null, sla));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", CALLER_DEPT)));
        when(workOrderLogMapper.selectList(any())).thenReturn(List.of());

        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool()));
        stub = new StubModelServer(
                StubModelServer.json(StubModelServer.toolCallTurn("call_real", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + ORDER_NO + "\"}")),
                // 关键：**引用 assignee 这条证据**（"为什么没人接"的招牌场景）
                StubModelServer.json(StubModelServer.finishTurn("TIMEOUT_SITUATION",
                        List.of("E1", "E2", "E3", "E4"), List.of())));
        AgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
        InvestigationAgent agent = new InvestigationAgent(model, registry, AgentLimits.s1Defaults());

        AgentRunResult result = agent.investigate(ctxOf(CALLER_DEPT), "这单为什么没人接？");

        assertEquals(AgentStatus.COMPLETED, result.status(),
                () -> "\"未分配\"是已知事实（D83），不得标未知；failure=" + result.failure());
    }

    @Test
    @DisplayName("assignee 有 id 但用户行不存在：真正的未知 → 标 unknown + 值说明查不到，且不撞允许未知")
    void assigneeIdWithoutUserRow_isRealUnknown() {
        LocalDateTime sla = LocalDateTime.of(2026, 10, 7, 12, 0);
        when(workOrderMapper.selectOne(any())).thenReturn(order("IN_PROGRESS", ASSIGNEE_ID, sla));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", CALLER_DEPT)));
        when(userMapper.selectById(ASSIGNEE_ID)).thenReturn(null);   // 有 id，但 t_user 无该行
        when(workOrderLogMapper.selectList(any())).thenReturn(List.of());

        ToolOutcome outcome = tool().execute(ctxOf(CALLER_DEPT), args());

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertTrue(outcome.unknownFacts().contains("order.assignee"),
                "有 id 查不到用户 = 真正的未知（不是空值）：" + outcome.facts().get("order.assignee"));
        assertTrue(outcome.facts().get("order.assignee").contains("查不到"),
                "值必须说明查不到，不得编一个假名：" + outcome.facts().get("order.assignee"));
    }
}
