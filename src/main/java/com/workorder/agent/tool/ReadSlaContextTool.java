package com.workorder.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.AgentTool;
import com.workorder.agent.ToolContext;
import com.workorder.agent.ToolOutcome;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderMapper;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **只读工具：SLA 上下文**（设计稿 `docs/agent-design/AGENT-DESIGN.md` L87）。
 *
 * <p>输入 `orderRef`（= 工单号）；返回：**当前存储的截止点 / 查询时刻 / 是否已过点 /
 * 现有扫描状态是否适用 / 当前规则值**。
 *
 * <p><b>它只读、不判定后果</b>：**绝不重算**（不按规则去改算一个"应该的截止点"）、
 * **绝不升级**（不触发任何状态迁移）、**绝不告警**。所以"存储值"和"规则值"是**两个并列的事实**，
 * 由报告去区分（§5.1 槽 07："区分当前规则、存储事实与历史未知"）——
 * 本工具**不**输出"应该的截止点"，那正是"重算"。
 *
 * <p><b>时钟可注入</b>：`sla.observed_at` / `sla.overdue` 用构造时传入的 {@link Clock}，
 * 测试可固定（否则"是否已过点"不可复现）。
 *
 * <p><b>空值与未知按 D83</b>：`sla_deadline` 为 NULL → 值"无 SLA 截止（NULL 未登记）" + `emptyFacts`
 * （**已知为空**，不是未知）；`sla.scan_applicable` 在库里**根本没有来源**（无扫描状态列）→ 显式未知；
 * 查不到规则配置 → 值"（无规则配置）" + `emptyFacts`（已知为空）。
 *
 * <p><b>事实键</b>：`sla.stored_deadline` / `sla.observed_at` / `sla.overdue` /
 * `sla.scan_applicable` / `sla.current_rule`。
 */
public final class ReadSlaContextTool implements AgentTool {

    public static final String NAME = "read_sla_context";

    /** 兜底规则：与 `WorkOrderServiceImpl` 的兜底口径一致（`OTHER` / 优先级 0）。 */
    private static final String FALLBACK_RULE_TYPE = "OTHER";
    private static final int FALLBACK_RULE_PRIORITY = 0;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WorkOrderMapper workOrderMapper;
    private final UserMapper userMapper;
    private final SlaConfigMapper slaConfigMapper;
    private final Clock clock;

    public ReadSlaContextTool(WorkOrderMapper workOrderMapper, UserMapper userMapper,
                              SlaConfigMapper slaConfigMapper) {
        this(workOrderMapper, userMapper, slaConfigMapper, Clock.systemDefaultZone());
    }

    /** 供测试注入固定时钟：否则"是否已过点"不可复现。 */
    public ReadSlaContextTool(WorkOrderMapper workOrderMapper, UserMapper userMapper,
                              SlaConfigMapper slaConfigMapper, Clock clock) {
        this.workOrderMapper = workOrderMapper;
        this.userMapper = userMapper;
        this.slaConfigMapper = slaConfigMapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按工单号读 SLA 上下文（只读）：存储的截止点、查询时刻、是否已过点、扫描状态是否适用、当前规则值；"
                + "不重算、不升级、不告警";
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

        WorkOrder order = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        Map<String, String> facts = new LinkedHashMap<>();
        if (order == null) {
            facts.put("order.exists", "false");
            return ToolOutcome.ok(facts);
        }
        if (!inCallerDepartment(ctx, order)) {
            return ToolOutcome.error("FORBIDDEN",
                    "工单不在调用者部门范围内（callerDeptId=" + ctx.callerDeptId() + "），拒绝返回任何 SLA 上下文");
        }

        Set<String> unknown = new LinkedHashSet<>();
        Set<String> empty = new LinkedHashSet<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);

        // ① 存储事实：**照抄存储值**，不按规则改算（"绝不重算"）
        java.time.LocalDateTime stored = order.getSlaDeadline();
        if (stored == null) {
            facts.put("sla.stored_deadline", "无 SLA 截止（NULL 未登记）");
            empty.add("sla.stored_deadline");           // D83：NULL 是**已知的空**，不是未知
        } else {
            facts.put("sla.stored_deadline", stored.format(TIME));
        }

        // ② 查询时刻（可注入时钟）
        facts.put("sla.observed_at", now.format(TIME));

        // ③ 是否已过点：只与**存储值**比；没有存储值就是"不适用"，而不是未知（NULL 是已知事实）
        if (stored == null) {
            facts.put("sla.overdue", "不适用（无 SLA 截止可比）");
        } else {
            facts.put("sla.overdue", stored.isBefore(now) ? "true" : "false");
        }

        // ④ 现有扫描状态是否适用：**库里没有这个来源**（无扫描状态列）→ 真正的未知（D83）
        facts.put("sla.scan_applicable", "未知（无扫描状态记录源）");
        unknown.add("sla.scan_applicable");

        // ⑤ 当前规则值（只读配置表，仍**不改算**截止点）：口径与 WorkOrderServiceImpl 的兜底一致
        SlaConfig rule = resolveRule(order);
        if (rule == null) {
            facts.put("sla.current_rule", "（无规则配置）");
            empty.add("sla.current_rule");
        } else {
            facts.put("sla.current_rule", "type=" + rule.getType() + "/priority=" + rule.getPriority()
                    + "：受理 " + rule.getAcceptMinutes() + " 分钟、完成 " + rule.getFinishMinutes() + " 分钟（当前规则值，非存储截止点的来源）");
        }

        return ToolOutcome.ok(facts, unknown, empty);
    }

    /** 规则查询：`(type, priority)` → 兜底 `(OTHER, 0)`；与业务侧同一口径，只读不改。 */
    private SlaConfig resolveRule(WorkOrder order) {
        String type = order.getType() == null || order.getType().isBlank() ? FALLBACK_RULE_TYPE : order.getType();
        int priority = order.getPriority() == null ? FALLBACK_RULE_PRIORITY : order.getPriority();
        SlaConfig rule = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, type)
                .eq(SlaConfig::getPriority, priority));
        if (rule == null) {
            rule = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                    .eq(SlaConfig::getType, FALLBACK_RULE_TYPE)
                    .eq(SlaConfig::getPriority, FALLBACK_RULE_PRIORITY));
        }
        return rule;
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
