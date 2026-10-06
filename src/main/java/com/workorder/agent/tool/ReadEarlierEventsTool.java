package com.workorder.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.AgentTool;
import com.workorder.agent.ToolContext;
import com.workorder.agent.ToolOutcome;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **只读工具：更早一页日志**（设计稿 `docs/agent-design/AGENT-DESIGN.md` L85）。
 *
 * <p>输入 `orderRef`（= 工单号）+ `cursor`；返回**更早一页日志，最多 {@value #MAX_PAGE_SIZE} 条**。
 *
 * <p><b>游标纪律</b>（L85 的"游标绑定本轮、工单及边界，不能猜或无限翻页"）：
 * <ul>
 *   <li>游标由**本工具签发**，携带 `工单号 + 边界（最早一条已读完的日志 id）`，并用
 *       `ToolContext.investigationId` 参与派生一个绑定摘要——**跨调查、跨工单、伪造的游标一律 `BAD_ARGUMENT`**；</li>
 *   <li>边界必须是**该工单真实存在的日志 id**，否则判为越界（不能拿一个编出来的数字去切开数据）；
 *       `investigationId` 从不下发给模型，所以绑定摘要在模型侧**不可伪造**；</li>
 *   <li>**不能无限翻页**：只有"还有更早的"时才签发下一页游标；到最早一页就没有游标了（走 `emptyFacts`，D83 口径）。</li>
 * </ul>
 *
 * <p><b>授权与 {@link OrderFactsTool} 同一口径</b>（§11-2：`callerDeptId` → 同部门提交人集合）；
 * 起点单不可见 → `FORBIDDEN` 且不返回任何日志内容。**不复用**详情接口的升级分支，**不**静默降级成"只看自己"。
 *
 * <p><b>事实键</b>：`order.logs_page` / `order.logs_page_has_more` / `order.logs_page_cursor`。
 */
public final class ReadEarlierEventsTool implements AgentTool {

    public static final String NAME = "read_earlier_events";
    /** 一页最多 20 条（设计稿 L85 的硬约束，不自行放宽）。 */
    public static final int MAX_PAGE_SIZE = 20;

    private static final String CURSOR_PREFIX = "re1";

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderLogMapper workOrderLogMapper;
    private final UserMapper userMapper;

    public ReadEarlierEventsTool(WorkOrderMapper workOrderMapper, WorkOrderLogMapper workOrderLogMapper,
                                 UserMapper userMapper) {
        this.workOrderMapper = workOrderMapper;
        this.workOrderLogMapper = workOrderLogMapper;
        this.userMapper = userMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按工单号翻到**更早一页**日志（最多 " + MAX_PAGE_SIZE + " 条）；继续翻页要带上一次返回的 cursor，"
                + "cursor 只能由本工具签发（跨工单/跨调查/伪造会被拒）";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of("type", "string", "description", "工单编号，格式 WO-yyyyMMdd-00001"),
                        "cursor", Map.of("type", "string",
                                "description", "上一页返回的游标；省略 = 从最新一页往前翻")),
                "required", List.of("orderNo"));
    }

    @Override
    public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
        String orderNo = arguments.path("orderNo").asText("");
        if (orderNo.isBlank()) {
            return ToolOutcome.error("BAD_ARGUMENT", "缺少必填参数 orderNo");
        }

        WorkOrder order = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        Map<String, String> facts = new LinkedHashMap<>();
        if (order == null) {
            facts.put("order.exists", "false");
            return ToolOutcome.ok(facts);
        }
        if (!inCallerDepartment(ctx, order)) {
            return ToolOutcome.error("FORBIDDEN",
                    "工单不在调用者部门范围内（callerDeptId=" + ctx.callerDeptId() + "），拒绝返回任何日志内容");
        }

        // ── 游标校验：跨调查 / 跨工单 / 越界 / 伪造 → 参数错误，**不执行** ──
        String cursor = arguments.path("cursor").asText("");
        long boundary = Long.MAX_VALUE;      // 省略游标 = 从最新一页往前
        if (!cursor.isBlank()) {
            Cursor parsed = parseCursor(cursor, ctx, orderNo);
            if (parsed.error != null) {
                return ToolOutcome.error("BAD_ARGUMENT", parsed.error);
            }
            if (!logIdBelongsToOrder(order.getId(), parsed.boundaryId)) {
                return ToolOutcome.error("BAD_ARGUMENT",
                        "游标边界越界：id=" + parsed.boundaryId + " 不是该工单的日志（不能按编造的边界切数据）");
            }
            boundary = parsed.boundaryId;
        }

        List<WorkOrderLog> rows = workOrderLogMapper.selectList(
                new LambdaQueryWrapper<WorkOrderLog>()
                        .eq(WorkOrderLog::getOrderId, order.getId())
                        .lt(WorkOrderLog::getId, boundary)
                        .orderByDesc(WorkOrderLog::getId)
                        .last("LIMIT " + (MAX_PAGE_SIZE + 1)));
        boolean hasMore = rows.size() > MAX_PAGE_SIZE;
        List<WorkOrderLog> page = hasMore ? new ArrayList<>(rows.subList(0, MAX_PAGE_SIZE)) : new ArrayList<>(rows);
        Collections.reverse(page);   // 页内按时间正序呈现（更早 → 较晚）

        Set<String> empty = new LinkedHashSet<>();
        if (page.isEmpty()) {
            facts.put("order.logs_page", "（无：已到最早一页）");
            empty.add("order.logs_page");
        } else {
            facts.put("order.logs_page", LogLines.render(page, userMapper));
        }
        // 已知事实：够不够下一页（不是未知——数据在那儿，只是还没读）
        facts.put("order.logs_page_has_more", hasMore ? "true" : "false");
        if (hasMore) {
            facts.put("order.logs_page_cursor", issueCursor(ctx, orderNo, page.get(0).getId()));
        } else {
            facts.put("order.logs_page_cursor", "（无：已到最早一页）");
            empty.add("order.logs_page_cursor");
        }
        return ToolOutcome.ok(facts, Set.of(), empty);
    }

    // ─────────────────────────── 游标 ───────────────────────────

    private record Cursor(Long boundaryId, String error) {
    }

    /**
     * 游标 = `re1.<base64url(orderNo)>.<boundaryId>.<bind>`。
     *
     * <p>`bind` 由 `investigationId + orderNo + boundaryId` 派生（8 位十六进制）。`investigationId`
     * **从不下发给模型**，所以模型无法构造出通过校验的游标——这就是"不能猜"的机器判据。
     */
    private static String issueCursor(ToolContext ctx, String orderNo, long boundaryId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(orderNo.getBytes(StandardCharsets.UTF_8));
        return CURSOR_PREFIX + "." + encoded + "." + boundaryId + "." + bind(ctx, orderNo, boundaryId);
    }

    private static Cursor parseCursor(String cursor, ToolContext ctx, String orderNo) {
        String[] parts = cursor.split("\\.");
        if (parts.length != 4 || !CURSOR_PREFIX.equals(parts[0])) {
            return new Cursor(null, "游标格式非法（只能使用本工具返回的 cursor）");
        }
        String decoded;
        long boundaryId;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            boundaryId = Long.parseLong(parts[2]);
        } catch (IllegalArgumentException e) {
            return new Cursor(null, "游标格式非法（只能使用本工具返回的 cursor）");
        }
        if (!orderNo.equals(decoded)) {
            return new Cursor(null, "游标与工单不匹配：游标属于 " + decoded + "，本次请求的是 " + orderNo);
        }
        if (!bind(ctx, orderNo, boundaryId).equals(parts[3])) {
            return new Cursor(null, "游标与本次调查不匹配（跨调查复用或伪造的游标一律拒绝）");
        }
        return new Cursor(boundaryId, null);
    }

    private static String bind(ToolContext ctx, String orderNo, long boundaryId) {
        String seed = ctx.investigationId() + "|" + orderNo + "|" + boundaryId;
        return String.format("%08x", seed.hashCode());
    }

    private boolean logIdBelongsToOrder(Long orderId, Long logId) {
        return workOrderLogMapper.selectCount(new LambdaQueryWrapper<WorkOrderLog>()
                .eq(WorkOrderLog::getOrderId, orderId)
                .eq(WorkOrderLog::getId, logId)) > 0;
    }

    /** 部门范围判定：与 {@link OrderFactsTool} 同一口径（`submitter_id ∈ 该部门用户 id 集`）。 */
    private boolean inCallerDepartment(ToolContext ctx, WorkOrder order) {
        Long deptId;
        try {
            deptId = Long.valueOf(ctx.callerDeptId());
        } catch (NumberFormatException e) {
            return false;
        }
        List<User> deptUsers = userMapper.selectList(
                new LambdaQueryWrapper<User>().eq(User::getDeptId, deptId));
        return deptUsers.stream().map(User::getId).anyMatch(id -> id.equals(order.getSubmitterId()));
    }
}
