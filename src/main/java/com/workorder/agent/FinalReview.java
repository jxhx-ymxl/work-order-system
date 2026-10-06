package com.workorder.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **最终短读取复核**（`docs/agent-design/AGENT-DESIGN.md` L213）。
 *
 * <p>报告通过校验、**在返回 COMPLETED 之前**，重新查一遍主单的关键业务字段，与证据里登记的值逐字段比对；
 * 被引用的**对照单**也要重查"是否仍在同一部门可见范围内"。任何不一致 → 终态 `INCOMPLETE(STATE_CHANGED)`，
 * 报告作废（`report == null`）、已核实的事实保留在 `evidence` 里。
 *
 * <p><b>为什么不是"只核一个 version"</b>（设计稿原话："version 单独不够：markTriageFailed 不递增 version"）：
 * version 是**并发控制字段**，它变了不代表业务事实变了、它不变也不代表业务事实没变（分诊失败路径就不递增）。
 * 所以本类的判据是**逐字段比对**，`version` **既不作为触发条件、也不作为"没变"的依据**。
 *
 * <p><b>记入指纹的字段</b>：就是**证据里真的登记了的那几个业务 fact**——主单的 `order.exists` /
 * `order.status` / `order.assignee` / `order.sla_deadline` / `order.accept_events`（用**同一个工具**重查，
 * 所以渲染口径天然一致，不会出现"复核用另一套格式化"）。
 * 设计稿还列了 `type` / `priority` / `triageStatus`，但**当前工具不产出这些事实键**，
 * 无从比对——**不假装比过**：它们一旦产物化，必须一并纳入（见 §3.2 的登记）。
 *
 * <p><b>不放进指纹的东西</b>：查询时刻、耗时、动态超时状态（§5：那类值会造成恒定误报），
 * 以及 `order.logs_page` 这类**依赖游标的分页切片**（切片边界是相对量，口径要先定）。
 *
 * <p><b>两路共用</b>：与 {@link EvidenceLedger} / {@link AgentReportValidator} 一样，是**同一个协作者**——
 * agent 与基线都调它，否则 S6 的对照就不公平。
 */
public final class FinalReview {

    /** 不做复核（测试/无库场景用；生产装配必须给真实实现）。 */
    public static final FinalReview NONE = new FinalReview(null, null, null);

    private final AgentToolRegistry tools;
    private final WorkOrderMapper workOrderMapper;
    private final UserMapper userMapper;
    private final String rootToolName;

    public FinalReview(AgentToolRegistry tools, WorkOrderMapper workOrderMapper, UserMapper userMapper) {
        this(tools, workOrderMapper, userMapper, OrderFactsTool.NAME);
    }

    public FinalReview(AgentToolRegistry tools, WorkOrderMapper workOrderMapper, UserMapper userMapper,
                       String rootToolName) {
        this.tools = tools;
        this.workOrderMapper = workOrderMapper;
        this.userMapper = userMapper;
        this.rootToolName = rootToolName;
    }

    public boolean isEnabled() {
        return tools != null && workOrderMapper != null && userMapper != null;
    }

    /**
     * 返回**发生变化的字段说明**；空列表 = 未发现变化（可以返回 COMPLETED）。
     *
     * <p>复核的 SQL 与时间**没有**计入工具预算（设计稿 L213 说"复核 SQL/时间计入总成本"）——
     * 这是一处**已知偏离**：计数口径会牵动 8 条按 `toolCalls` 写死的用例，登记在 §3.2 / D90，另轮落地。
     */
    public List<String> findChanges(ToolContext ctx, String rootOrderNo, List<AgentEvidence> evidence) {
        if (!isEnabled()) {
            return List.of();
        }
        List<String> changes = new ArrayList<>();

        // ① 主单：用**同一个工具**重查 → 渲染口径与证据完全一致
        ToolOutcome fresh = tools.execute(ctx,
                new ModelToolCall("final-review", rootToolName,
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                                .put("orderNo", rootOrderNo)));
        if (!fresh.ok()) {
            return List.of("范围: 最终复核重查主单被拒（" + fresh.errorCode() + "）");
        }
        Map<String, String> freshFacts = fresh.facts();
        for (AgentEvidence item : evidence) {
            String current = freshFacts.get(item.fact());
            if (current != null && !current.equals(item.value())) {
                changes.add(item.fact() + ": 证据登记「" + item.value() + "」→ 现在「" + current + "」");
            }
        }

        // ② 被引用的对照单：重查是否仍在同一部门可见范围内
        Set<Long> deptUserIds = deptUserIds(ctx);
        for (String peerNo : peerOrderNos(evidence)) {
            WorkOrder peer = workOrderMapper.selectOne(
                    new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, peerNo));
            if (peer == null) {
                changes.add("对照单 " + peerNo + ": 已被删除");
            } else if (peer.getSubmitterId() == null || !deptUserIds.contains(peer.getSubmitterId())) {
                changes.add("对照单 " + peerNo + ": 提交人已不在调用者部门可见范围内");
            }
        }
        return changes;
    }

    /** 从已登记的证据里取"对照单单号"——只看两个关系事实，值里就是枚举出来的单号。 */
    private static List<String> peerOrderNos(List<AgentEvidence> evidence) {
        List<String> nos = new ArrayList<>();
        for (AgentEvidence item : evidence) {
            if ("dept.assignee_open_order_nos".equals(item.fact())
                    || "dept.submitter_recent_order_nos".equals(item.fact())) {
                for (String candidate : item.value().split("[、,，]")) {
                    String trimmed = candidate.trim();
                    if (trimmed.startsWith("WO-")) {
                        nos.add(trimmed);
                    }
                }
            }
        }
        return nos;
    }

    private Set<Long> deptUserIds(ToolContext ctx) {
        Long deptId;
        try {
            deptId = Long.valueOf(ctx.callerDeptId());
        } catch (NumberFormatException e) {
            return Set.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getDeptId, deptId))
                .stream().map(User::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
