package com.workorder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.agent.tool.DeptComparisonTool;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 第二个真实工具（{@link DeptComparisonTool}）的判据——§1 第三题「可按需扩查同部门对照」。
 *
 * <p>范围口径（`AGENT-DESIGN.md:33`）："同部门对照**只是可见范围内的记录，不能解释为处理人全局工作量**，
 * 更不能直接解释为延误原因"——所以事实值必须自带范围限定文字，且**不进任何类型的 requiredFacts**。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("第二个真实工具：同部门对照（可选证据）")
class DeptComparisonToolTest {

    private static final String START_ORDER_NO = "WO-20260607-00001";
    private static final long SUBMITTER_ID = 100L;
    private static final long ASSIGNEE_ID = 200L;
    private static final long OTHER_SUBMITTER_ID = 300L;
    private static final long DEPT = 7L;
    private static final long OTHER_DEPT = 9L;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private UserMapper userMapper;

    private DeptComparisonTool tool() {
        return new DeptComparisonTool(workOrderMapper, userMapper);
    }

    private static ToolContext ctxOf(long deptId) {
        return ToolContext.ofDepartment("inv-peer-1", "42", String.valueOf(deptId));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode args() {
        return JSON.createObjectNode().put("orderNo", START_ORDER_NO);
    }

    private static WorkOrder order(long id, String orderNo, String status, Long submitterId,
                                   Long assigneeId, LocalDateTime createdAt) {
        WorkOrder order = new WorkOrder();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setStatus(status);
        order.setSubmitterId(submitterId);
        order.setAssigneeId(assigneeId);
        order.setCreatedAt(createdAt);
        return order;
    }

    private static User user(long id, long deptId) {
        User user = new User();
        user.setId(id);
        user.setDeptId(deptId);
        return user;
    }

    private static WorkOrder start(LocalDateTime at) {
        return order(1L, START_ORDER_NO, "IN_PROGRESS", SUBMITTER_ID, ASSIGNEE_ID, at);
    }

    /**
     * 起点单 + 部门成员 + 候选集（候选集的过滤在工具里做，所以这里给"原始"行）。
     *
     * <p>用 `lenient()`：不同用例走的路径不同——FORBIDDEN / 处理人范围外两类**根本不会查到候选集**，
     * 严格桩会把这些"备好但没用上"的桩判为 UnnecessaryStubbing 而报错（本轮实测：3 个 error）。
     */
    private void stub(WorkOrder startOrder, List<User> deptMembers, List<WorkOrder> candidates) {
        lenient().when(workOrderMapper.selectOne(any())).thenReturn(startOrder);
        lenient().when(userMapper.selectList(any())).thenReturn(deptMembers);
        lenient().when(workOrderMapper.selectList(any())).thenReturn(candidates);
    }

    private static List<User> deptMembers() {
        return List.of(user(SUBMITTER_ID, DEPT), user(ASSIGNEE_ID, DEPT));
    }

    @Test
    @DisplayName("同部门正例：只数本部门可见范围内、本处理人的其它未结工单（终态不计、本单不计）")
    void sameDepartment_countsOnlyInScopeOpenOrders() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 6, 9, 0);
        stub(start(base), deptMembers(), List.of(
                start(base),                                                              // 本单：排除
                order(2L, "WO-20260607-00002", "ACCEPTED", SUBMITTER_ID, ASSIGNEE_ID, base.plusMinutes(1)),
                order(3L, "WO-20260607-00003", "ESCALATED_ADMIN", SUBMITTER_ID, ASSIGNEE_ID, base.plusMinutes(2)),
                order(4L, "WO-20260607-00004", "CLOSED", SUBMITTER_ID, ASSIGNEE_ID, base.plusMinutes(3)),   // 终态：不计
                order(5L, "WO-20260607-00005", "RELEASED", SUBMITTER_ID, ASSIGNEE_ID, base.plusMinutes(4)))); // 终态：不计

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), args());

        assertTrue(outcome.ok(), outcome.errorMessage());
        String count = outcome.facts().get("dept.assignee_open_count");
        assertTrue(count.contains("本部门可见范围内") && count.contains("2 张"),
                "计数必须自带范围限定文字，且终态不计：" + count);
        String nos = outcome.facts().get("dept.assignee_open_order_nos");
        assertTrue(nos.indexOf("WO-20260607-00003") < nos.indexOf("WO-20260607-00002"),
                "按 created_at 倒序（新的在前）：" + nos);
        assertTrue(outcome.unknownFacts().isEmpty(), "本工具没有'查不到'来源，不应标未知");
    }

    @Test
    @DisplayName("跨部门不计入：同处理人但别的部门提交的工单不进对照")
    void otherDepartmentOrders_areNotCounted() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 6, 9, 0);
        stub(start(base), deptMembers(), List.of(
                order(2L, "WO-20260607-00002", "ACCEPTED", SUBMITTER_ID, ASSIGNEE_ID, base.plusMinutes(1)),
                order(3L, "WO-20260607-00003", "ACCEPTED", OTHER_SUBMITTER_ID, ASSIGNEE_ID, base.plusMinutes(2)),
                order(4L, "WO-20260607-00004", "IN_PROGRESS", OTHER_SUBMITTER_ID, ASSIGNEE_ID, base.plusMinutes(3))));

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), args());

        assertTrue(outcome.facts().get("dept.assignee_open_count").contains("1 张"),
                "别的部门提交的单不在本部门可见范围内：" + outcome.facts().get("dept.assignee_open_count"));
        assertTrue(outcome.facts().get("dept.assignee_open_order_nos").contains("WO-20260607-00002"));
        assertFalse(outcome.facts().get("dept.assignee_open_order_nos").contains("WO-20260607-00003"));
    }

    @Test
    @DisplayName("升级单仍受部门限制：起点单是 ESCALATED_ADMIN 且跨部门 → FORBIDDEN，不返回对照")
    void escalatedStartOrder_isStillDepartmentScoped() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 6, 9, 0);
        WorkOrder escalated = order(1L, START_ORDER_NO, "ESCALATED_ADMIN", SUBMITTER_ID, ASSIGNEE_ID, base);
        stub(escalated, List.of(user(999L, OTHER_DEPT)), List.of());   // 调用者部门里没有提交人

        ToolOutcome outcome = tool().execute(ctxOf(OTHER_DEPT), args());

        assertFalse(outcome.ok(), "升级单不得扩大可见范围");
        assertEquals("FORBIDDEN", outcome.errorCode());
        assertTrue(outcome.facts().isEmpty(), "拒绝时不得返回任何对照数据");
    }

    @Test
    @DisplayName("起点单不可见 → FORBIDDEN，且 facts 为空")
    void invisibleStartOrder_isForbidden() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 6, 9, 0);
        stub(start(base), List.of(user(999L, OTHER_DEPT)), List.of());

        ToolOutcome outcome = tool().execute(ctxOf(OTHER_DEPT), args());

        assertFalse(outcome.ok());
        assertEquals("FORBIDDEN", outcome.errorCode());
        assertTrue(outcome.facts().isEmpty());
    }

    @Test
    @DisplayName("计数 0 / 列表为空：是已知值 + emptyFacts，**不是 unknown**（钉住 D83 通则）")
    void zeroCount_isKnownValueAndEmptyList() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 6, 9, 0);
        stub(start(base), deptMembers(), List.of(start(base)));   // 只有本单

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), args());

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertTrue(outcome.facts().get("dept.assignee_open_count").contains("0 张"),
                "0 是已知值：" + outcome.facts().get("dept.assignee_open_count"));
        assertTrue(outcome.emptyFacts().contains("dept.assignee_open_order_nos"),
                "空列表走 emptyFacts（完整事实）");
        assertTrue(outcome.unknownFacts().isEmpty(), "空 ≠ 未知（D83）");
    }

    @Test
    @DisplayName("处理人不在可见范围内：不计入、不是报错，且值里说明「范围外不计」")
    void assigneeOutsideScope_isNotCountedButExplained() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 6, 9, 0);
        // 起点单提交人在部门内（可见），但处理人 999 不在部门成员里
        WorkOrder startOrder = order(1L, START_ORDER_NO, "IN_PROGRESS", SUBMITTER_ID, 999L, base);
        stub(startOrder, deptMembers(), List.of(
                order(2L, "WO-20260607-00002", "ACCEPTED", SUBMITTER_ID, 999L, base.plusMinutes(1))));

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), args());

        assertTrue(outcome.ok(), "处理人范围外是「不计入」，不是报错：" + outcome.errorMessage());
        assertTrue(outcome.facts().get("dept.assignee_open_count").contains("范围外不计"),
                outcome.facts().get("dept.assignee_open_count"));
        assertTrue(outcome.emptyFacts().contains("dept.assignee_open_order_nos"));
    }
}
