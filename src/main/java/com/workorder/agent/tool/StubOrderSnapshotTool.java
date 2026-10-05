package com.workorder.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.AgentTool;
import com.workorder.agent.SensitiveDataRedactor;
import com.workorder.agent.ToolContext;
import com.workorder.agent.ToolOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 虚构工具：查工单当前状态快照。
 *
 * <p><b>数据是写死的常量</b>（S1 不接业务库，见 `docs/AGENT-PLAN.md` §10）。它存在的意义是让协议链
 * 跑在真实形状的数据上：多字段事实、可格式校验的参数、明确的参数错误。
 */
public final class StubOrderSnapshotTool implements AgentTool {

    public static final String NAME = "stub_order_snapshot";
    /** 不存在的工单：只返回 {@code order.exists=false}——用来构造"必需事实覆盖不了"的路径。 */
    public static final String MISSING_ORDER_NO = "WO-20260607-99999";
    /** 告警记录源不可用的工单：{@code order.alert_count} 以"显式未知"返回（该事实允许未知）。 */
    public static final String NO_ALERT_SOURCE_ORDER_NO = "WO-20260607-00002";
    /** 未分配的工单：{@code order.assignee} 以"显式未知"返回（§3.1 的禁止项要用它：未分配 ⇒ 不得建议联系处理人）。 */
    public static final String UNASSIGNED_ORDER_NO = "WO-20260607-00004";

    private static final Pattern ORDER_NO = Pattern.compile("WO-\\d{8}-\\d{5}");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询工单当前状态快照（S1 虚构数据）：是否存在、状态、处理人、SLA 截止、告警次数";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of("type", "string", "description", "工单编号，格式 WO-yyyyMMdd-00001")),
                "required", List.of("orderNo"));
    }

    @Override
    public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
        String orderNo = arguments.path("orderNo").asText("");
        if (orderNo.isBlank()) {
            return ToolOutcome.error("BAD_ARGUMENT", "缺少必填参数 orderNo");
        }
        if (!ORDER_NO.matcher(orderNo).matches()) {
            return ToolOutcome.error("BAD_ARGUMENT", "orderNo 格式不合法（期望 WO-yyyyMMdd-00001）：" + orderNo);
        }

        Map<String, String> facts = new LinkedHashMap<>();
        if (MISSING_ORDER_NO.equals(orderNo)) {
            facts.put("order.exists", "false");
            return ToolOutcome.ok(facts);
        }
        facts.put("order.exists", "true");
        facts.put("order.status", "IN_PROGRESS");
        if (UNASSIGNED_ORDER_NO.equals(orderNo)) {
            facts.put("order.assignee", "未分配");
            facts.put("order.sla_deadline", "2026-10-06T12:00:00+08:00");
            facts.put("order.alert_count", "2");
            return ToolOutcome.ok(facts, java.util.Set.of("order.assignee"));
        }
        // 处理人只给脱敏显示名：外发白名单里没有 user_id / username / 手机号（§4.4）
        facts.put("order.assignee", SensitiveDataRedactor.maskName("张伟"));
        facts.put("order.sla_deadline", "2026-10-06T12:00:00+08:00");
        if (NO_ALERT_SOURCE_ORDER_NO.equals(orderNo)) {
            facts.put("order.alert_count", "未知（告警记录源不可用）");
            return ToolOutcome.ok(facts, java.util.Set.of("order.alert_count"));
        }
        facts.put("order.alert_count", "2");
        return ToolOutcome.ok(facts);
    }
}
