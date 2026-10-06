package com.workorder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.agent.tool.ReadSlaContextTool;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * **只读工具：SLA 上下文**（设计稿 L87）。判据：**不重算**（存储值与规则值并列）/ 空值走 D83 /
 * 扫描状态是"无来源"的真未知 / 跨部门拒绝。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("只读工具③：SLA 上下文（只读、不重算）")
class ReadSlaContextToolTest {

    private static final String ORDER_NO = "WO-20261006-00007";
    private static final long SUBMITTER_ID = 100L;
    private static final long DEPT = 7L;
    private static final long OTHER_DEPT = 9L;
    /** 固定时钟 T0 = 2026-10-06 12:00（本地时区）。 */
    private static final Clock CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 6, 12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SlaConfigMapper slaConfigMapper;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReadSlaContextTool tool() {
        return new ReadSlaContextTool(workOrderMapper, userMapper, slaConfigMapper, CLOCK);
    }

    private static ToolContext ctxOf(long deptId) {
        return ToolContext.ofDepartment("inv-sla-1", "42", String.valueOf(deptId));
    }

    private static WorkOrder order(LocalDateTime slaDeadline) {
        WorkOrder order = new WorkOrder();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setSubmitterId(SUBMITTER_ID);
        order.setStatus("IN_PROGRESS");
        order.setType("NETWORK");
        order.setPriority(0);
        order.setSlaDeadline(slaDeadline);
        return order;
    }

    private static User user(long id, String username, long deptId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setDeptId(deptId);
        return user;
    }

    private static SlaConfig rule(String type, int priority, int accept, int finish) {
        SlaConfig config = new SlaConfig();
        config.setType(type);
        config.setPriority(priority);
        config.setAcceptMinutes(accept);
        config.setFinishMinutes(finish);
        return config;
    }

    private static String args() {
        return "{\"orderNo\":\"" + ORDER_NO + "\"}";
    }

    @Test
    @DisplayName("不重算：存储截止点与当前规则值**并列返回**，返回的是存储值而不是按规则改算的值")
    void doesNotRecalculateStoredDeadline() throws Exception {
        // 存储值 = T0-2h（已过）；规则值 = 完成 120 分钟。若"重算"，会得到一个 T0+2h 的假截止点。
        when(workOrderMapper.selectOne(any())).thenReturn(order(LocalDateTime.of(2026, 10, 6, 10, 0)));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", DEPT)));
        when(slaConfigMapper.selectOne(any())).thenReturn(rule("NETWORK", 0, 30, 120));

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), JSON.readTree(args()));

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertEquals("2026-10-06 10:00", outcome.facts().get("sla.stored_deadline"), "必须是**存储值**");
        assertEquals("2026-10-06 12:00", outcome.facts().get("sla.observed_at"), "查询时刻来自可注入时钟");
        assertEquals("true", outcome.facts().get("sla.overdue"));
        String ruleValue = outcome.facts().get("sla.current_rule");
        assertTrue(ruleValue.contains("完成 120 分钟"), ruleValue);
        assertTrue(ruleValue.contains("非存储截止点的来源"), "两个事实必须能被区分开：" + ruleValue);
        // "重算"的机器判据：不得出现第二个"截止点"口径的事实键
        assertFalse(outcome.facts().containsKey("sla.recalculated_deadline"), outcome.facts().toString());
        assertFalse(outcome.facts().containsKey("order.sla_deadline"), "本工具不改写起点单事实键：" + outcome.facts());
    }

    @Test
    @DisplayName("D83：无 SLA 截止是**已知的空**（emptyFacts），不是未知；「是否已过点」不适用")
    void nullDeadlineIsKnownEmptyNotUnknown() throws Exception {
        when(workOrderMapper.selectOne(any())).thenReturn(order(null));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", DEPT)));
        when(slaConfigMapper.selectOne(any())).thenReturn(rule("NETWORK", 0, 30, 120));

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), JSON.readTree(args()));

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertTrue(outcome.emptyFacts().contains("sla.stored_deadline"), "NULL = 已知的空");
        assertFalse(outcome.unknownFacts().contains("sla.stored_deadline"), "空 ≠ 未知（D83）");
        assertTrue(outcome.facts().get("sla.stored_deadline").contains("无 SLA 截止"));
        assertTrue(outcome.facts().get("sla.overdue").contains("不适用"), outcome.facts().get("sla.overdue"));
        assertFalse(outcome.unknownFacts().contains("sla.overdue"), "没有可比对象是已知结论，不是未知");
    }

    @Test
    @DisplayName("扫描状态：库里没有这个来源 → 显式未知（D83 的'查不到'）")
    void scanApplicabilityIsExplicitlyUnknown() throws Exception {
        when(workOrderMapper.selectOne(any())).thenReturn(order(LocalDateTime.of(2026, 10, 6, 10, 0)));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", DEPT)));
        when(slaConfigMapper.selectOne(any())).thenReturn(rule("NETWORK", 0, 30, 120));

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), JSON.readTree(args()));

        assertTrue(outcome.unknownFacts().contains("sla.scan_applicable"), outcome.toString());
        assertTrue(outcome.facts().get("sla.scan_applicable").contains("无扫描状态记录源"));
    }

    @Test
    @DisplayName("查不到规则配置 → 已知的空（不是未知）：规则值事实走 emptyFacts")
    void missingRuleIsKnownEmpty() throws Exception {
        when(workOrderMapper.selectOne(any())).thenReturn(order(LocalDateTime.of(2026, 10, 6, 10, 0)));
        when(userMapper.selectList(any())).thenReturn(List.of(user(SUBMITTER_ID, "zhangsan", DEPT)));
        lenient().when(slaConfigMapper.selectOne(any())).thenReturn(null);

        ToolOutcome outcome = tool().execute(ctxOf(DEPT), JSON.readTree(args()));

        assertTrue(outcome.ok(), outcome.errorMessage());
        assertTrue(outcome.emptyFacts().contains("sla.current_rule"));
        assertTrue(outcome.facts().get("sla.current_rule").contains("无规则配置"));
    }

    @Test
    @DisplayName("跨部门调用 → FORBIDDEN，且不返回任何 SLA 上下文")
    void crossDepartmentIsForbidden() throws Exception {
        when(workOrderMapper.selectOne(any())).thenReturn(order(LocalDateTime.of(2026, 10, 6, 10, 0)));
        when(userMapper.selectList(any())).thenReturn(List.of(user(999L, "other", OTHER_DEPT)));

        ToolOutcome outcome = tool().execute(ctxOf(OTHER_DEPT), JSON.readTree(args()));

        assertFalse(outcome.ok());
        assertEquals("FORBIDDEN", outcome.errorCode());
        assertTrue(outcome.facts().isEmpty(), "拒绝时不得夹带任何 SLA 事实");
    }
}
