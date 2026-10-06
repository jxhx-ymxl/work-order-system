package com.workorder.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.AgentTool;
import com.workorder.agent.ToolContext;
import com.workorder.agent.ToolOutcome;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * **关系查询工具**（对应设计稿 L86 `find_related_orders`：`relation` + 可选 cursor）。
 *
 * <p><b>一张表、一个工具带 parameter**——不拆成两个工具**：设计稿 L82-L87 的工具表就是四行，
 * 拆开会让 §3.2 的工具数与设计表对不上（本轮 D 条目未开：`DECISIONS.md` 不在本轮允许的改动范围里，
 * 取舍与代价写在本注释与 §3.2）。
 *
 * <p><b>两条关系（L91 / L92）</b>：
 * <ul>
 *   <li>{@value #RELATION_SAME_ASSIGNEE_ACTIVE}：主单当前处理人在本部门可见、状态
 *       **恰好 {@code ACCEPTED} / {@code IN_PROGRESS}** 的其他单（**不是**"去掉终态"那种更宽的口径，
 *       所以 {@code ESCALATED_ADMIN} **不算**）；排除主单；**无处理人返回 {@code NOT_APPLICABLE}**。</li>
 *   <li>{@value #RELATION_SAME_SUBMITTER_RECENT}：主单**提交人**近 {@value #RECENT_DAYS} 天创建的其他可见单，排除主单。
 *       {@value #RECENT_DAYS} 天是设计稿 L92 的**初始建议**——**待实测**；**不是**相似语义检索。</li>
 * </ul>
 *
 * <p><b>锚定与授权</b>：入参只有 {@code orderNo} + {@code relation}——关系**锚定主单**，
 * **不接受**任意人 / 任意部门 / SQL 片段（多传 {@code assigneeId} / {@code userId} / {@code deptId} 一律无效，
 * 因为实现根本不读它们）。部门范围与 {@link OrderFactsTool} 同一口径（§11-2 / D85）：
 * `callerDeptId` → 同部门用户 id 集 → 候选单 `submitter_id ∈ 集合`；起点单不可见 → `FORBIDDEN` 且不返回任何数据。
 *
 * <p><b>摘要与分页（L86 / L93 / L94）</b>：一页**最多 {@value #MAX_RELATED_ORDERS} 条**，
 * 按 `created_at` / `id` 降序；**多取 1 条**只用于判断"还有更多"，**不为了展示总数去扫全库**——
 * 所以值里写的是"**本页** N 条"，不是"总共 N 条"。
 *
 * <p><b>事实键</b>：{@code dept.assignee_open_count} / {@code dept.assignee_open_order_nos}（第一条关系）、
 * {@code dept.submitter_recent_count} / {@code dept.submitter_recent_order_nos}（第二条关系）。
 * 两条关系都是**可选证据，不进任何类型的必需事实**（§1 第三题：证据足够即停，不要求每次扩查）。
 */
public final class DeptComparisonTool implements AgentTool {

    public static final String NAME = "query_dept_peer_orders";
    public static final String RELATION_SAME_ASSIGNEE_ACTIVE = "SAME_ASSIGNEE_ACTIVE";
    public static final String RELATION_SAME_SUBMITTER_RECENT = "SAME_SUBMITTER_RECENT";

    /** 一页最多 10 条（设计稿 L86："摘要，最多 10 条"）。 */
    public static final int MAX_RELATED_ORDERS = 10;
    /** 近 30 天——设计稿 L92 的**初始建议**，**待实测**（不是相似语义检索）。 */
    public static final int RECENT_DAYS = 30;

    /** `SAME_ASSIGNEE_ACTIVE` 的状态集合：**恰好 ACCEPTED / IN_PROGRESS**（L91），不是"去掉终态"。 */
    private static final Set<String> ACTIVE_STATUSES = Set.of("ACCEPTED", "IN_PROGRESS");

    private final WorkOrderMapper workOrderMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    public DeptComparisonTool(WorkOrderMapper workOrderMapper, UserMapper userMapper) {
        this(workOrderMapper, userMapper, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟：`SAME_SUBMITTER_RECENT` 的"近 30 天"必须可复现。 */
    public DeptComparisonTool(WorkOrderMapper workOrderMapper, UserMapper userMapper, Clock clock) {
        this.workOrderMapper = workOrderMapper;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按关系查同部门对照摘要（最多 " + MAX_RELATED_ORDERS + " 条）："
                + RELATION_SAME_ASSIGNEE_ACTIVE + "=主单处理人的其它进行中工单；"
                + RELATION_SAME_SUBMITTER_RECENT + "=主单提交人近 " + RECENT_DAYS + " 天创建的其它单。"
                + "关系锚定主单，不接受指定任意人 / 部门；数字只代表【本部门可见范围、本页】";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of("type", "string", "description", "起点（主）工单编号"),
                        "relation", Map.of("type", "string",
                                "enum", List.of(RELATION_SAME_ASSIGNEE_ACTIVE, RELATION_SAME_SUBMITTER_RECENT),
                                "description", "关系类型；只允许这两个值")),
                "required", List.of("orderNo", "relation"));
    }

    @Override
    public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
        String orderNo = arguments.path("orderNo").asText("");
        if (orderNo.isBlank()) {
            return ToolOutcome.error("BAD_ARGUMENT", "缺少必填参数 orderNo");
        }
        String relation = arguments.path("relation").asText("");
        // 非法关系**先判**：不执行任何查询（"不接受任意人/部门/SQL" 的机器判据就在这条上）
        if (!RELATION_SAME_ASSIGNEE_ACTIVE.equals(relation) && !RELATION_SAME_SUBMITTER_RECENT.equals(relation)) {
            return ToolOutcome.error("BAD_ARGUMENT",
                    "relation 非法：" + relation + "（只允许 " + RELATION_SAME_ASSIGNEE_ACTIVE
                            + " / " + RELATION_SAME_SUBMITTER_RECENT + "）");
        }

        WorkOrder start = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        Map<String, String> facts = new LinkedHashMap<>();
        if (start == null) {
            facts.put("order.exists", "false");
            return ToolOutcome.ok(facts);
        }
        if (!startOrderVisible(ctx, start)) {
            return ToolOutcome.error("FORBIDDEN",
                    "起点工单不在调用者部门范围内（callerDeptId=" + ctx.callerDeptId() + "），拒绝返回任何对照数据");
        }

        return RELATION_SAME_SUBMITTER_RECENT.equals(relation)
                ? sameSubmitterRecent(start)
                : sameAssigneeActive(start, ctx);
    }

    // ─────────────────────────── 关系①：同处理人 · 进行中 ───────────────────────────

    private ToolOutcome sameAssigneeActive(WorkOrder start, ToolContext ctx) {
        Map<String, String> facts = new LinkedHashMap<>();
        Set<String> empty = new LinkedHashSet<>();
        Long assigneeId = start.getAssigneeId();

        if (assigneeId == null) {
            // L91：无处理人返回 NOT_APPLICABLE（**不是 0**——0 会被读成"该处理人没有别的单"）
            facts.put("dept.assignee_open_count", "NOT_APPLICABLE");
            facts.put("dept.assignee_open_order_nos", "（无：主单当前未分配，该关系不适用）");
            empty.add("dept.assignee_open_order_nos");
            return ToolOutcome.ok(facts, Set.of(), empty);
        }

        Set<Long> deptUserIds = deptUserIds(ctx);
        if (!deptUserIds.contains(assigneeId)) {
            // 处理人不在可见范围内 → 不计入（不是报错），并把范围说明写进值里
            facts.put("dept.assignee_open_count", "本部门可见范围内本页 0 张（主单处理人不在本部门，范围外不计）");
            facts.put("dept.assignee_open_order_nos", "（无：本部门可见范围内没有该处理人的其它进行中工单）");
            empty.add("dept.assignee_open_order_nos");
            return ToolOutcome.ok(facts, Set.of(), empty);
        }

        List<WorkOrder> page = fetchRelated(start,
                candidate -> assigneeId.equals(candidate.getAssigneeId())
                        && ACTIVE_STATUSES.contains(candidate.getStatus())
                        && deptUserIds.contains(candidate.getSubmitterId()));
        return pageOutcome(facts, empty, page, "dept.assignee_open_count", "dept.assignee_open_order_nos",
                "主单处理人的其它进行中工单");
    }

    // ─────────────────────────── 关系②：同提交人 · 近 30 天 ───────────────────────────

    private ToolOutcome sameSubmitterRecent(WorkOrder start) {
        Map<String, String> facts = new LinkedHashMap<>();
        Set<String> empty = new LinkedHashSet<>();
        Long submitterId = start.getSubmitterId();
        LocalDateTime since = LocalDateTime.now(clock).minusDays(RECENT_DAYS);

        List<WorkOrder> page = fetchRelated(start,
                candidate -> submitterId != null && submitterId.equals(candidate.getSubmitterId())
                        && candidate.getCreatedAt() != null
                        && !candidate.getCreatedAt().isBefore(since));
        return pageOutcome(facts, empty, page, "dept.submitter_recent_count", "dept.submitter_recent_order_nos",
                "主单提交人近 " + RECENT_DAYS + " 天创建的其它单");
    }

    // ─────────────────────────── 公共：取页与出页 ───────────────────────────

    /**
     * 取候选页：**SQL 侧只做"更宽"的预过滤**（不变量：wrapper 只允许比 Java 谓词更宽，窄了会漏行且 Java 救不回来），
     * 真正的判定在 {@code predicate} 里；`.last(LIMIT 11)` 是"**多取 1 条**用于判断 hasMore"（L94），
     * **不为了展示总数扫描全库**。
     */
    private List<WorkOrder> fetchRelated(WorkOrder start, java.util.function.Predicate<WorkOrder> predicate) {
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .ne(WorkOrder::getId, start.getId())
                        .orderByDesc(WorkOrder::getCreatedAt)
                        .orderByDesc(WorkOrder::getId)
                        .last("LIMIT " + (MAX_RELATED_ORDERS + 1)));
        return candidates.stream()
                .filter(candidate -> !start.getId().equals(candidate.getId()))   // 本单在 Java 侧也排除（可证伪）
                .filter(predicate)
                .sorted(Comparator
                        .comparing(WorkOrder::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(WorkOrder::getId, Comparator.reverseOrder()))
                .limit(MAX_RELATED_ORDERS)
                .toList();
    }

    private ToolOutcome pageOutcome(Map<String, String> facts, Set<String> empty, List<WorkOrder> page,
                                    String countKey, String nosKey, String what) {
        // 只报"本页多少条"与"还有没有更多"，**不报总数**（L94：不为展示总数扫全库）
        boolean hasMore = page.size() == MAX_RELATED_ORDERS;
        facts.put(countKey, "本部门可见范围内本页 " + page.size() + " 张（" + what + "；不含本单，"
                + "上限 " + MAX_RELATED_ORDERS + " 张" + (hasMore ? "，本页已满、存在更多" : "") + "；不等于全部未结单）");
        if (page.isEmpty()) {
            facts.put(nosKey, "（无：本部门可见范围内没有符合条件的其它单）");
            empty.add(nosKey);
        } else {
            facts.put(nosKey, page.stream().map(WorkOrder::getOrderNo).collect(Collectors.joining("、")));
        }
        return ToolOutcome.ok(facts, Set.of(), empty);
    }

    /** 起点单可见性：与 {@link OrderFactsTool} 同一口径（`submitter_id ∈ 该部门用户 id 集`）。 */
    private boolean startOrderVisible(ToolContext ctx, WorkOrder start) {
        return start.getSubmitterId() != null && deptUserIds(ctx).contains(start.getSubmitterId());
    }

    private Set<Long> deptUserIds(ToolContext ctx) {
        Long deptId;
        try {
            deptId = Long.valueOf(ctx.callerDeptId());
        } catch (NumberFormatException e) {
            return Set.of();   // 范围载体不可解析 → 空集（调用方会判 FORBIDDEN）
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getDeptId, deptId))
                .stream().map(User::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
