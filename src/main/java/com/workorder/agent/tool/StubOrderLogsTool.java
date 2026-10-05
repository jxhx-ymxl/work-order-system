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
 * 虚构工具：查工单日志（分页），本片只用它产出"接单事件"这一类事实。
 *
 * <p>参数上界（{@code size ≤ 50}）是刻意留的：`docs/AGENT-PLAN.md` §2.1 指出"分页查询的成本不是
 * {@code LIMIT 10} 就完事"，所以工具的接口边界上就限制单次读取量，坏参数走 {@code BAD_ARGUMENT}。
 */
public final class StubOrderLogsTool implements AgentTool {

    public static final String NAME = "stub_order_logs";
    public static final int MAX_PAGE_SIZE = 50;
    /** 日志源不可用的工单：{@code order.accept_events} 以"显式未知"返回（该类型**不允许**未知）。 */
    public static final String NO_LOG_SOURCE_ORDER_NO = "WO-20260607-00003";
    /** 从未被接单的工单：{@code order.accept_events} 是**空**（完整事实，不是未知）——§3.1 的禁止项用它。 */
    public static final String NEVER_ACCEPTED_ORDER_NO = "WO-20260607-00005";

    private static final Pattern ORDER_NO = Pattern.compile("WO-\\d{8}-\\d{5}");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "分页查询工单日志（S1 虚构数据），含接单/处理事件序列";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of("type", "string", "description", "工单编号"),
                        "page", Map.of("type", "integer", "description", "页码，从 1 开始"),
                        "size", Map.of("type", "integer", "description", "每页条数，1.." + MAX_PAGE_SIZE)),
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
        if (arguments.has("page") && !arguments.get("page").canConvertToInt()) {
            return ToolOutcome.error("BAD_ARGUMENT", "page 必须是整数");
        }
        if (arguments.has("size") && !arguments.get("size").canConvertToInt()) {
            return ToolOutcome.error("BAD_ARGUMENT", "size 必须是整数");
        }
        int page = arguments.path("page").asInt(1);
        int size = arguments.path("size").asInt(20);
        if (page < 1) {
            return ToolOutcome.error("BAD_ARGUMENT", "page 必须 >= 1，收到 " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            return ToolOutcome.error("BAD_ARGUMENT", "size 必须在 1.." + MAX_PAGE_SIZE + " 之间，收到 " + size);
        }

        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("order.exists", "true");
        if (NO_LOG_SOURCE_ORDER_NO.equals(orderNo)) {
            facts.put("order.accept_events", "未知（日志源不可用）");
            return ToolOutcome.ok(facts, java.util.Set.of("order.accept_events"));
        }
        if (NEVER_ACCEPTED_ORDER_NO.equals(orderNo)) {
            facts.put("order.accept_events", "从未接单");
            return ToolOutcome.ok(facts, java.util.Set.of(), java.util.Set.of("order.accept_events"));
        }
        facts.put("order.accept_events",
                "2 次接单：2026-10-05T09:12:00+08:00 由 " + SensitiveDataRedactor.maskName("李娜")
                        + " 接单后释放；2026-10-05T10:03:00+08:00 由 " + SensitiveDataRedactor.maskName("王强") + " 接单");
        return ToolOutcome.ok(facts);
    }
}
