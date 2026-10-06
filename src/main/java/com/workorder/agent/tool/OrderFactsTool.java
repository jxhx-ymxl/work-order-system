package com.workorder.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.AgentTool;
import com.workorder.agent.SensitiveDataRedactor;
import com.workorder.agent.ToolContext;
import com.workorder.agent.ToolOutcome;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **第一个真实工具**（S2）：按工单编号取五条事实，并强制部门范围。
 *
 * <p>事实集（`docs/S2-TOOL-DATA-MAP.md` v2 确认可支撑）：`order.exists` / `order.status` /
 * `order.assignee`（**脱敏显示名**，§4.4）/ `order.sla_deadline` / `order.accept_events`。
 *
 * <p><b>授权</b>（§11-2 / D79 / D81）：范围**只**由 `ctx.callerDeptId()` 表达——
 * 取该部门的用户 id 集（`t_user.dept_id`），要求 `t_work_order.submitter_id` 落在集合内
 * （形状同 `WorkOrderServiceImpl.applyRoleFilters:523-537`，但范围来自**受理层快照**，不重读调用者）。
 * <ul>
 *   <li>**不复用** `canViewDetail:641-643` 的升级分支：§1 第二题规定"升级状态**不扩大**可见范围"，
 *       所以 `ESCALATED_ADMIN` 与其它状态**同一判定**；</li>
 *   <li>**不照抄** `applyRoleFilters:540-542` 的"没有过滤器就只看自己"静默降级：缺部门在**受理期**就该失败
 *       （`ToolContext` 构造期已保证 `callerDeptId` 非空），执行期**不允许**悄悄缩小或放宽；</li>
 *   <li>调用者是否"主管"、是否是待分配池的潜在处理人，**都不参与**判定（多分支不扩权）。</li>
 * </ul>
 *
 * <p><b>接单行序列</b>：读 `t_work_order_log` 的 `action ∈ {ACCEPT, ASSIGN, RELEASE, MANAGE}` 行（按时间正序）。
 * 不能用当前 `assignee_id` 代替——释放后二次接单会有第二行 `ACCEPT`（`version` 递增）。
 * 两条渲染约束：① `RELEASE` 行的 `operatorId = 0` 是**系统操作**（`OrderLogAspect:52` 的降级），
 * 不得渲染成某个人；② `MANAGE` 只可能出现在升级单（`manageEscalatedOrder`），它**不改变**部门范围。
 */
public final class OrderFactsTool implements AgentTool {

    public static final String NAME = "get_order_facts";
    /** 表示"谁处理过"的行类型；只用 ACCEPT 会漏掉主管指派（ASSIGN）与系统释放（RELEASE）。 */
    private static final Set<String> HANDLING_ACTIONS = Set.of("ACCEPT", "ASSIGN", "RELEASE", "MANAGE");
    /** 系统操作人（`OrderLogAspect.resolveOperatorId` 在无登录上下文时降级为 0）。 */
    private static final long SYSTEM_OPERATOR_ID = 0L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderLogMapper workOrderLogMapper;
    private final UserMapper userMapper;

    public OrderFactsTool(WorkOrderMapper workOrderMapper, WorkOrderLogMapper workOrderLogMapper, UserMapper userMapper) {
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
        return "按工单编号取事实：是否存在、状态、当前处理人（脱敏）、SLA 截止、接单/指派/释放行序列";
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
            // 失败要落在业务错误上：不许"返回 200 + 空事实"让模型自己猜（§3.2 的判据要求）
            return ToolOutcome.error("FORBIDDEN",
                    "工单不在调用者部门范围内（callerDeptId=" + ctx.callerDeptId() + "），拒绝返回任何事实");
        }

        Set<String> unknown = new LinkedHashSet<>();
        Set<String> empty = new LinkedHashSet<>();
        facts.put("order.exists", "true");
        facts.put("order.status", order.getStatus());

        Long assigneeId = order.getAssigneeId();
        if (assigneeId == null) {
            facts.put("order.assignee", "未分配");
            unknown.add("order.assignee");     // §3.1：ORDER_STATUS 允许 assignee 未知，但必须显式标注
        } else {
            facts.put("order.assignee", maskDisplayName(assigneeId));
        }

        if (order.getSlaDeadline() == null) {
            facts.put("order.sla_deadline", "无 SLA 截止（NULL 未登记）");
            unknown.add("order.sla_deadline"); // 不得编一个日期；TIMEOUT_SITUATION 也不允许该事实未知（会判未完成）
        } else {
            facts.put("order.sla_deadline", order.getSlaDeadline().format(TIME));
        }

        // 告警次数：**没有列**（映射表 §1），只能显式未知——不近似、不编造
        facts.put("order.alert_count", "未知（无告警计数记录源）");
        unknown.add("order.alert_count");

        List<WorkOrderLog> handlingLogs = workOrderLogMapper.selectList(
                new LambdaQueryWrapper<WorkOrderLog>()
                        .eq(WorkOrderLog::getOrderId, order.getId())
                        .in(WorkOrderLog::getAction, HANDLING_ACTIONS)
                        .orderByAsc(WorkOrderLog::getCreatedAt)
                        .orderByAsc(WorkOrderLog::getId));
        if (handlingLogs.isEmpty()) {
            facts.put("order.accept_events", "从未接单或指派（无 ACCEPT/ASSIGN/RELEASE/MANAGE 记录）");
            empty.add("order.accept_events");   // 空是**完整事实**，不算未知（§3.1）
        } else {
            facts.put("order.accept_events", renderHandlingLogs(handlingLogs));
        }

        return ToolOutcome.ok(facts, unknown, empty);
    }

    /** 部门范围判定：`submitter_id ∈ 该部门用户 id 集`（形状同 applyRoleFilters，范围来自受理层快照）。 */
    private boolean inCallerDepartment(ToolContext ctx, WorkOrder order) {
        Long deptId;
        try {
            deptId = Long.valueOf(ctx.callerDeptId());
        } catch (NumberFormatException e) {
            return false;   // 范围载体不可解析 → 拒绝（不静默放宽成"没有过滤条件"）
        }
        List<User> deptUsers = userMapper.selectList(
                new LambdaQueryWrapper<User>().eq(User::getDeptId, deptId));
        return deptUsers.stream().map(User::getId).anyMatch(id -> id.equals(order.getSubmitterId()));
    }

    /** 处理人只给脱敏显示名（§4.4 白名单：不外发 username / name 原值、不外发 user_id）。 */
    private String maskDisplayName(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return "已分配（显示名不可得）";
        }
        return SensitiveDataRedactor.maskName(user.getUsername());
    }

    /** 行序列渲染：`动作@时间 by 操作人`；operatorId=0 一律写"系统操作"（OrderLogAspect:52 的降级语义）。 */
    private String renderHandlingLogs(List<WorkOrderLog> logs) {
        List<String> rendered = new ArrayList<>();
        for (WorkOrderLog log : logs) {
            String who = log.getOperatorId() != null && log.getOperatorId() == SYSTEM_OPERATOR_ID
                    ? "系统操作"
                    : maskDisplayName(log.getOperatorId());
            String at = log.getCreatedAt() == null ? "时间未知" : log.getCreatedAt().format(TIME);
            rendered.add(log.getAction() + "@" + at + " by " + who);
        }
        return String.join("；", rendered);
    }
}
