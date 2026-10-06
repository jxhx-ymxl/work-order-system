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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * **第二个真实工具**：同部门对照工单查询（§1 第三题「可按需扩查同部门对照」，非每次必查）。
 *
 * <p><b>它返回的是可选证据，不进任何类型的 requiredFacts</b>——扩查是可选动作，不是完成前提
 * （`AGENT-LEARNING-EVAL.md:29`："模型据结果决定是否查同处理人对照｜不是每张单都必查"）。
 *
 * <p><b>范围限定写在事实值里</b>（`AGENT-DESIGN.md:33`）："同部门对照**只是可见范围内的记录，
 * 不能解释为处理人全局工作量**，更不能直接解释为延误原因"——所以两个事实的值都自带
 * "本部门可见范围内"字样，**不得**写成"该处理人有 N 张未结单"。
 *
 * <p><b>授权与 {@link OrderFactsTool} 同一口径</b>（不另写一套）：`callerDeptId` → 同部门用户 id 集 →
 * 起点单 `submitter_id ∈ 集合`；起点单不可见 → `FORBIDDEN` 且不返回任何对照数据；
 * **不复用** `canViewDetail` 的升级分支；**不照抄**"无过滤器就只看自己"的静默降级。
 *
 * <p><b>边界（初值，待实测）</b>：单号列表 ≤ {@value #MAX_ORDER_NOS} 条、按 `created_at` 倒序；
 * 只统计**未结**状态（{@code CLOSED} / {@code RELEASED} 为终态，见 {@code Status} 枚举与 D01 的"RELEASED 死路"）；
 * 计数为 0 是**已知值**、列表为空走 `emptyFacts`（D83 通则：空值不是未知）。
 * 截断时事实值会写"存在下一页"（`AGENT-DESIGN.md:203` 的示例口径）。
 */
public final class DeptComparisonTool implements AgentTool {

    public static final String NAME = "query_dept_peer_orders";
    /** 单号列表上限：**初值，待实测**（§1 第三题："条数、时间范围未定稿"）。 */
    public static final int MAX_ORDER_NOS = 20;
    /** 未结状态 = `Status` 枚举去掉终态 `CLOSED` / `RELEASED`。 */
    private static final Set<String> OPEN_STATUSES =
            Set.of("PENDING", "ACCEPTED", "IN_PROGRESS", "AWAIT_APPROVAL", "ESCALATED_ADMIN");

    private final WorkOrderMapper workOrderMapper;
    private final UserMapper userMapper;

    public DeptComparisonTool(WorkOrderMapper workOrderMapper, UserMapper userMapper) {
        this.workOrderMapper = workOrderMapper;
        this.userMapper = userMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按起点单号查同一处理人在【本部门可见范围内】的其它未结工单数（对照用，可选扩查；"
                + "该数字不代表处理人全局工作量）";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of("type", "string", "description", "起点工单编号")),
                "required", List.of("orderNo"));
    }

    @Override
    public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
        String orderNo = arguments.path("orderNo").asText("");
        if (orderNo.isBlank()) {
            return ToolOutcome.error("BAD_ARGUMENT", "缺少必填参数 orderNo");
        }

        WorkOrder start = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        Map<String, String> facts = new LinkedHashMap<>();
        if (start == null) {
            facts.put("order.exists", "false");
            return ToolOutcome.ok(facts);
        }

        Long deptId;
        try {
            deptId = Long.valueOf(ctx.callerDeptId());
        } catch (NumberFormatException e) {
            return ToolOutcome.error("FORBIDDEN", "部门范围载体无法解析，拒绝返回任何对照数据");
        }
        Set<Long> deptUserIds = userMapper.selectList(
                        new LambdaQueryWrapper<User>().eq(User::getDeptId, deptId))
                .stream().map(User::getId).collect(Collectors.toCollection(LinkedHashSet::new));

        // 起点单本身也要过同一可见性：不可见 → FORBIDDEN，且**不返回任何对照数据**
        if (start.getSubmitterId() == null || !deptUserIds.contains(start.getSubmitterId())) {
            return ToolOutcome.error("FORBIDDEN",
                    "起点工单不在调用者部门范围内（callerDeptId=" + ctx.callerDeptId() + "），拒绝返回任何对照数据");
        }

        Set<String> empty = new LinkedHashSet<>();
        Long assigneeId = start.getAssigneeId();
        String countValue;
        List<WorkOrder> listed = List.of();

        if (assigneeId == null) {
            countValue = "本部门可见范围内 0 张未结工单（起点单当前未分配，无对照对象）";
        } else if (!deptUserIds.contains(assigneeId)) {
            // 处理人不在可见范围内 → **不计入**（不是报错），并说明范围
            countValue = "本部门可见范围内 0 张未结工单（起点单处理人不在本部门，范围外不计）";
        } else {
            List<WorkOrder> candidates = workOrderMapper.selectList(
                    new LambdaQueryWrapper<WorkOrder>()
                            .eq(WorkOrder::getAssigneeId, assigneeId)
                            .ne(WorkOrder::getId, start.getId()));
            List<WorkOrder> inScopeOpen = candidates.stream()
                    // 起点单必须在 Java 侧排除：`ne(getId, start.getId())` 只是 SQL 侧预过滤，
                    // 单测里 mapper 被 mock（wrapper 被忽略）——谓词留在被测代码里才可证伪（同夹具的教训）。
                    .filter(candidate -> !start.getId().equals(candidate.getId()))
                    .filter(candidate -> OPEN_STATUSES.contains(candidate.getStatus()))
                    .filter(candidate -> candidate.getSubmitterId() != null
                            && deptUserIds.contains(candidate.getSubmitterId()))
                    .sorted(Comparator
                            .comparing(WorkOrder::getCreatedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(WorkOrder::getId, Comparator.reverseOrder()))
                    .toList();
            boolean truncated = inScopeOpen.size() > MAX_ORDER_NOS;
            listed = inScopeOpen.stream().limit(MAX_ORDER_NOS).toList();
            countValue = "本部门可见范围内 " + inScopeOpen.size() + " 张未结工单（不含本单"
                    + (truncated ? "；单号列表仅列最近 " + MAX_ORDER_NOS + " 张，存在下一页" : "") + "）";
        }

        facts.put("dept.assignee_open_count", countValue);
        if (listed.isEmpty()) {
            facts.put("dept.assignee_open_order_nos", "（无：本部门可见范围内没有该处理人的其它未结工单）");
            empty.add("dept.assignee_open_order_nos");
        } else {
            facts.put("dept.assignee_open_order_nos",
                    listed.stream().map(WorkOrder::getOrderNo).collect(Collectors.joining("、")));
        }
        return ToolOutcome.ok(facts, Set.of(), empty);
    }
}
