package com.workorder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.agent.tool.ReadEarlierEventsTool;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * **只读工具：更早一页日志**（设计稿 L85）。四条判据：一页 ≤20 条 / 越界与伪造游标被拒 /
 * 跨工单游标被拒 / 跨部门拒绝。
 *
 * <p>夹具说明：mock 会忽略 wrapper（含 `lt(id, boundary)` 与 `LIMIT`），所以**由测试自己模拟"数据库会返回什么页"**
 * ——这正是"边界由游标决定"这件事唯一能被证伪的写法。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("只读工具②：更早一页日志（游标纪律）")
class ReadEarlierEventsToolTest {

    private static final String ORDER_NO = "WO-20261006-00006";
    private static final String OTHER_ORDER_NO = "WO-20261006-00016";
    private static final long SUBMITTER_ID = 100L;
    private static final long DEPT = 7L;
    private static final long OTHER_DEPT = 9L;

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderLogMapper workOrderLogMapper;
    @Mock
    private UserMapper userMapper;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReadEarlierEventsTool tool() {
        return new ReadEarlierEventsTool(workOrderMapper, workOrderLogMapper, userMapper);
    }

    private static ToolContext ctxOf(long deptId) {
        return ToolContext.ofDepartment("inv-read-1", "42", String.valueOf(deptId));
    }

    private static WorkOrder order() {
        WorkOrder order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setSubmitterId(SUBMITTER_ID);
        order.setStatus("IN_PROGRESS");
        return order;
    }

    private static User user(long id, String username, long deptId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDeptId(deptId);
        return user;
    }

    /** id 越大越新；这里造 1..25 共 25 条日志。 */
    private static List<WorkOrderLog> logsDesc(int fromId, int toId) {
        List<WorkOrderLog> logs = new ArrayList<>();
        for (int id = fromId; id >= toId; id--) {
            WorkOrderLog log = new WorkOrderLog();
            log.setId((long) id);
            log.setOrderId(1L);
            log.setOrderNo(ORDER_NO);
            log.setAction(id % 2 == 0 ? "ACCEPT" : "ASSIGN");
            log.setOperatorId(200L);
            log.setCreatedAt(LocalDateTime.of(2026, 10, 6, 9, 0).plusMinutes(id));
            logs.add(log);
        }
        return logs;
    }

    private void happyPathMocks() {
        lenient().when(workOrderMapper.selectOne(any())).thenReturn(order());
        lenient().when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", DEPT)));
        lenient().when(userMapper.selectById(any())).thenReturn(user(200L, "admin", DEPT));
    }

    private static String args(String orderNo, String cursor) {
        return cursor == null
                ? "{\"orderNo\":\"" + orderNo + "\"}"
                : "{\"orderNo\":\"" + orderNo + "\",\"cursor\":\"" + cursor + "\"}";
    }

    private static String pageValue(Map<String, String> facts) {
        return facts.get("order.logs_page");
    }

    @Test
    @DisplayName("第一页 ≤20 条且带下一页游标；用游标翻到更早一页（到最早就没有游标了）")
    void pagesBackwardsAtMostTwentyPerPage() throws Exception {
        happyPathMocks();
        // 第 1 次查询：最新 21 条（LIMIT 21）→ 只取 20 条、hasMore=true
        // 第 2 次查询：边界 6 之前的 5 条 → hasMore=false
        when(workOrderLogMapper.selectList(any())).thenReturn(logsDesc(25, 5), logsDesc(5, 1));
        lenient().when(workOrderLogMapper.selectCount(any())).thenReturn(1L);

        ReadEarlierEventsTool tool = tool();
        ToolOutcome first = tool.execute(ctxOf(DEPT), JSON.readTree(args(ORDER_NO, null)));

        assertTrue(first.ok(), first.errorMessage());
        String firstPage = pageValue(first.facts());
        assertEquals(20, firstPage.split("；").length, "一页最多 20 条：" + firstPage);
        assertTrue(firstPage.contains("ACCEPT@2026-10-06 09:06"), "含本页最早那条（id=6，偶数 → ACCEPT）：" + firstPage);
        assertFalse(firstPage.contains("@2026-10-06 09:05 by"), "id=5 属于**更早一页**，不该出现在本页：" + firstPage);
        assertEquals("true", first.facts().get("order.logs_page_has_more"));
        String cursor = first.facts().get("order.logs_page_cursor");
        assertFalse(first.emptyFacts().contains("order.logs_page_cursor"), "还有更早的就必须给游标");

        ToolOutcome second = tool.execute(ctxOf(DEPT), JSON.readTree(args(ORDER_NO, cursor)));

        assertTrue(second.ok(), second.errorMessage());
        assertEquals(5, pageValue(second.facts()).split("；").length, "第二页就是更早的那 5 条");
        assertEquals("false", second.facts().get("order.logs_page_has_more"));
        assertTrue(second.emptyFacts().contains("order.logs_page_cursor"), "到最早一页：游标按 D83 走 emptyFacts");
        assertTrue(second.facts().get("order.logs_page_cursor").contains("已到最早一页"));
    }

    @Test
    @DisplayName("伪造的游标（格式对、绑定错）→ BAD_ARGUMENT，不执行查询")
    void forgedCursorIsRejected() throws Exception {
        happyPathMocks();

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), JSON.readTree(args(ORDER_NO, "re1." + enc(ORDER_NO) + ".6.deadbeef")));

        assertFalse(outcome.ok());
        assertEquals("BAD_ARGUMENT", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("不匹配"), outcome.errorMessage());
    }

    @Test
    @DisplayName("越界的游标（绑定对、但边界不是该工单的日志 id）→ BAD_ARGUMENT，不执行")
    void outOfRangeCursorIsRejected() throws Exception {
        happyPathMocks();
        when(workOrderLogMapper.selectList(any())).thenReturn(logsDesc(25, 5));
        // 合法签发一个游标（边界 = 6）……
        String cursor = tool().execute(ctxOf(DEPT), JSON.readTree(args(ORDER_NO, null)))
                .facts().get("order.logs_page_cursor");
        // ……但该边界在库里不存在（模拟"越界/不属于该工单"）
        lenient().when(workOrderLogMapper.selectCount(any())).thenReturn(0L);

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), JSON.readTree(args(ORDER_NO, cursor)));

        assertFalse(outcome.ok());
        assertEquals("BAD_ARGUMENT", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("越界"), outcome.errorMessage());
    }

    @Test
    @DisplayName("跨工单复用游标 → BAD_ARGUMENT（游标绑定工单）")
    void cursorFromAnotherOrderIsRejected() throws Exception {
        happyPathMocks();
        when(workOrderLogMapper.selectList(any())).thenReturn(logsDesc(25, 5));
        lenient().when(workOrderLogMapper.selectCount(any())).thenReturn(1L);
        String cursor = tool().execute(ctxOf(DEPT), JSON.readTree(args(ORDER_NO, null)))
                .facts().get("order.logs_page_cursor");

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), JSON.readTree(args(OTHER_ORDER_NO, cursor)));

        assertFalse(outcome.ok());
        assertEquals("BAD_ARGUMENT", outcome.errorCode());
        assertTrue(outcome.errorMessage().contains("工单不匹配"), outcome.errorMessage());
    }

    @Test
    @DisplayName("跨部门调用 → FORBIDDEN，且不返回任何日志内容")
    void crossDepartmentIsForbidden() throws Exception {
        when(workOrderMapper.selectOne(any())).thenReturn(order());
        when(userMapper.selectList(any())).thenReturn(List.of(user(999L, "other", OTHER_DEPT)));

        ToolOutcome outcome = tool().execute(ctxOf(OTHER_DEPT), JSON.readTree(args(ORDER_NO, null)));

        assertFalse(outcome.ok());
        assertEquals("FORBIDDEN", outcome.errorCode());
        assertTrue(outcome.facts().isEmpty(), "拒绝时不得夹带任何日志内容");
    }

    private static String enc(String orderNo) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(orderNo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
