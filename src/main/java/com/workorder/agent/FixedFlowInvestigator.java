package com.workorder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.agent.tool.DeptComparisonTool;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.common.enums.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **强固定流程基线**（`docs/agent-design/AGENT-LEARNING-EVAL.md` §3.1，L104-118）。
 *
 * <p>基线是"**代码决定下一步**"的条件工作流，不是"只给一张详情逼它失败"。它复用 agent 的**同一套零件**
 * （硬要求，否则 S6 的成对比较没有意义）：同一两个工具（{@link OrderFactsTool} + {@link DeptComparisonTool}，
 * 经同一个 {@link AgentToolRegistry}）、同一 {@link ToolContext}（部门口径完全一致，**不自写授权**）、
 * 同一证据登记 {@link EvidenceLedger}、同一校验器 {@link AgentReportValidator}、同一上限口径 {@link AgentLimits}
 * （至少计入**工具调用次数**；允许实际调用量不同）、同一终态规则（非 `COMPLETED` ⇒ `report == null` + 原因码，
 * 由 {@link AgentRunResult} 构造期强制）。渲染不在本类里——调用方喂同一份 {@link AgentReport} 给
 * {@link AgentReportRenderer} 即可。
 *
 * <p><b>起点单是结构化入参、不是从问题里抠出来的</b>（设计稿 L80："入口固定预读一次主工单，注册 root 引用"）；
 * 问题文本**只用于分类**。<b>固定取证顺序</b>：入口预读起点单 →（问题涉及超时/转手时）同部门对照；
 * 证据足够即停，不强制把工具都查一遍（§3.1 L113）。**预读的这一次计入工具成本**（L80：两组实验均计预读成本）。
 *
 * <p><b>任务分类用透明规则</b>（关键词，首版不引入模型；§3.1 L118：若将来引入意图分类模型，
 * 必须把它的调用与耗时计入基线）。
 *
 * <p><b>本版未实现的手册条目</b>（还没有对应工具，登记为待办）：L110 的"早期一页"、L112 的"同提交人近期记录"；
 * L109 的 SLA 读取已由 {@link OrderFactsTool} 的 `order.sla_deadline` 覆盖。
 */
public final class FixedFlowInvestigator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentToolRegistry tools;
    private final AgentLimits limits;
    /** 最终短读取复核（L213）：与 agent **同一个协作者**，否则 S6 的对照不公平。 */
    private final FinalReview finalReview;
    /** 工具调用前的权限重校验（L116 第 3 条）：同样与 agent **共用同一个协作者**。 */
    private final PermissionRecheck permissionRecheck;

    public FixedFlowInvestigator(AgentToolRegistry tools, AgentLimits limits) {
        this(tools, limits, FinalReview.NONE, PermissionRecheck.NONE);
    }

    public FixedFlowInvestigator(AgentToolRegistry tools, AgentLimits limits, FinalReview finalReview) {
        this(tools, limits, finalReview, PermissionRecheck.NONE);
    }

    public FixedFlowInvestigator(AgentToolRegistry tools, AgentLimits limits, FinalReview finalReview,
                                 PermissionRecheck permissionRecheck) {
        this.tools = tools;
        this.limits = limits;
        this.finalReview = finalReview;
        this.permissionRecheck = permissionRecheck;
    }

    public AgentRunResult investigate(ToolContext ctx, String rootOrderNo, String question) {
        Map<String, AgentEvidence> evidence = new LinkedHashMap<>();
        int toolCalls = 0;

        // 入口固定预读（L80）：root 引用来自结构化入参，注册主工单事实；这一次计成本。
        AgentRunResult revoked = revokedIfAny(ctx, evidence, toolCalls);
        if (revoked != null) {
            return revoked;
        }
        ToolOutcome rootFacts = call(ctx, OrderFactsTool.NAME, rootOrderNo, evidence, toolCalls);
        toolCalls++;
        if (!rootFacts.ok()) {
            return toolFailure(rootFacts, evidence, toolCalls);
        }

        AgentProblemType problemType = classify(question);
        if (needsPeerComparison(problemType)) {
            if (toolCalls + 1 > limits.maxToolCalls()) {
                return budgetExceeded(evidence, toolCalls);
            }
            AgentRunResult revokedNow = revokedIfAny(ctx, evidence, toolCalls);
            if (revokedNow != null) {
                return revokedNow;
            }
            ToolOutcome peer = callPeerComparison(ctx, rootOrderNo, evidence, toolCalls);
            toolCalls++;
            if (!peer.ok()) {
                return toolFailure(peer, evidence, toolCalls);
            }
        }

        AgentReport report = new AgentReport(problemType, citableEvidenceIds(problemType, evidence),
                suggestionsFor(problemType, evidence));
        List<String> problems = AgentReportValidator.validate(problemType,
                report.evidenceIds(), report.suggestionIds(), evidence);
        if (!problems.isEmpty()) {
            return AgentRunResult.failed("REPORT_INVALID", String.join("；", problems),
                    new ArrayList<>(evidence.values()), toolCalls, 0, 1);
        }
        // 最终短读取复核（L213）：与 agent 走**同一个协作者**；不一致 → 报告作废、事实保留。
        List<String> changes = finalReview.findChanges(ctx, rootOrderNo, new ArrayList<>(evidence.values()));
        if (!changes.isEmpty()) {
            return AgentRunResult.incomplete("STATE_CHANGED",
                    "最终短读取发现业务状态变化：" + String.join("；", changes),
                    new ArrayList<>(evidence.values()), toolCalls, 0, 1);
        }
        return AgentRunResult.completed(report, new ArrayList<>(evidence.values()), toolCalls, 0, 1);
    }

    private static AgentProblemType classify(String question) {
        String q = question == null ? "" : question;
        if (containsAny(q, "超时", "为什么没", "没人接", "没人处理", "没处理完", "一直没")) {
            return AgentProblemType.TIMEOUT_SITUATION;
        }
        if (containsAny(q, "谁处理", "谁接", "处理过", "转过", "几手", "经手")) {
            return AgentProblemType.REASSIGN_HISTORY;
        }
        // 2026-10-06 开发集调优（**只改分类规则、不引入模型**，依据 docs/AGENT-LEARNING-EVAL.md L118）：
        //   · "处理人"    ← DEV-05「这单的处理人是谁？」期望 ORDER_STATUS（§3.1 把 order.assignee 列为该类型的必需事实）
        //     反例风险：把"帮我催一下处理人"这类**请求动作**的问法也归到 ORDER_STATUS。
        //     可接受的理由：ORDER_STATUS 只输出已核实事实 + 缺口，不产生动作、也不给原因（§1 第四题），
        //     误分的代价是"答成一张状态事实卡"，不是编造结论；且 TIMEOUT_SITUATION / REASSIGN_HISTORY 先判，不会被抢走。
        //   · "什么情况"  ← DEV-06「…现在什么情况？」期望 ORDER_STATUS（D82：工单不存在也要能以"不存在"收尾）
        //     反例风险：把"什么情况会导致超时"这类**问原因**的问法归到 ORDER_STATUS；
        //     但含"超时/为什么没/没人接"的问法会先命中 TIMEOUT_SITUATION（本表按序判定），所以实际风险很小。
        if (containsAny(q, "到哪一步", "进展", "状态", "到哪", "现在怎么样", "处理人", "什么情况")) {
            return AgentProblemType.ORDER_STATUS;
        }
        return AgentProblemType.UNSUPPORTED;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsPeerComparison(AgentProblemType problemType) {
        return problemType == AgentProblemType.TIMEOUT_SITUATION
                || problemType == AgentProblemType.REASSIGN_HISTORY;
    }

    /**
     * 固定建议（条件分支）：必须自己避开 §11-4 的**禁止项**——否则报告会被同一校验器判无效，
     * 那是"基线自己写错"，会被 S6 误读成"基线更弱"。
     */
    private static List<String> suggestionsFor(AgentProblemType problemType, Map<String, AgentEvidence> evidence) {
        return switch (problemType) {
            case ORDER_STATUS -> isAwaitApproval(evidence)
                    // 状态机决定方向（`frontend/CLAUDE.md` §3.2 状态转移表）：AWAIT_APPROVAL（待验收）时
                    // 「验收通过 / 驳回」只允许**提交人**做 —— 下一步在提交人侧。
                    // 此时给 CONTACT_ASSIGNEE 是错的：处理人已经交完验收了（这正是"这单现在到哪一步"要说的下一步）。
                    ? List.of(AgentSuggestion.WAIT_FOR_SUBMITTER_ACCEPTANCE.name())
                    : hasUsableAssignee(evidence)
                    ? List.of(AgentSuggestion.CONTACT_ASSIGNEE.name())
                    : List.of(AgentSuggestion.ESCALATE_TO_DEPT_ADMIN.name());
            case TIMEOUT_SITUATION -> List.of(AgentSuggestion.ESCALATE_TO_DEPT_ADMIN.name());
            case REASSIGN_HISTORY -> hasEmptyAcceptEvents(evidence)
                    ? List.of(AgentSuggestion.WAIT_FOR_CLAIM.name())
                    : List.of(AgentSuggestion.CONTACT_ASSIGNEE.name());
            case UNSUPPORTED -> List.of();
        };
    }

    /**
     * 工单是否处于「待验收」（`AWAIT_APPROVAL`）——**触发条件**：已登记的事实 `order.status` 等于该状态。
     *
     * <p>判据只看**证据里的状态事实**（与其它分支同源：不看问题文本、不另查库），状态名取枚举
     * {@link Status#AWAIT_APPROVAL} 而不是字面量——状态改名时这里会跟着编译失败，不会静默失配。
     * 状态未知（`unknown`）时判 false：宁可退回原分支，也不按"可能待验收"给方向。
     */
    private static boolean isAwaitApproval(Map<String, AgentEvidence> evidence) {
        return evidence.values().stream()
                .anyMatch(item -> "order.status".equals(item.fact())
                        && !item.unknown()
                        && Status.AWAIT_APPROVAL.name().equals(item.value()));
    }

    private static boolean hasUsableAssignee(Map<String, AgentEvidence> evidence) {
        return evidence.values().stream()
                .anyMatch(item -> "order.assignee".equals(item.fact()) && !item.unknown() && !item.empty());
    }

    private static boolean hasEmptyAcceptEvents(Map<String, AgentEvidence> evidence) {
        return evidence.values().stream()
                .anyMatch(item -> "order.accept_events".equals(item.fact()) && item.empty());
    }

    /** 经**同一个** registry 调用**同一个**工具；callId 固定，便于与 agent 轨迹对照。 */
    private ToolOutcome call(ToolContext ctx, String toolName, String orderNo,
                             Map<String, AgentEvidence> evidence, int callIndex) {
        return call(ctx, toolName, MAPPER.createObjectNode().put("orderNo", orderNo), evidence, callIndex);
    }

    /** 关系查询：基线固定用 `SAME_ASSIGNEE_ACTIVE`（设计稿 L86 的 relation 参数；代码决定，不靠模型选）。 */
    private ToolOutcome callPeerComparison(ToolContext ctx, String orderNo,
                                           Map<String, AgentEvidence> evidence, int callIndex) {
        var arguments = MAPPER.createObjectNode()
                .put("orderNo", orderNo)
                .put("relation", DeptComparisonTool.RELATION_SAME_ASSIGNEE_ACTIVE);
        return call(ctx, DeptComparisonTool.NAME, arguments, evidence, callIndex);
    }

    private ToolOutcome call(ToolContext ctx, String toolName, com.fasterxml.jackson.databind.node.ObjectNode arguments,
                             Map<String, AgentEvidence> evidence, int callIndex) {
        ToolOutcome outcome = tools.execute(ctx, new ModelToolCall("fixed-" + (callIndex + 1), toolName, arguments));
        if (outcome.ok()) {
            EvidenceLedger.recordInto(evidence, outcome);   // 与 agent 共用同一登记机制
        }
        return outcome;
    }

    private AgentRunResult budgetExceeded(Map<String, AgentEvidence> evidence, int toolCalls) {
        // 与 agent 同名的原因码（§3.4：两者只统一上限，不要求实际调用量相同）
        return AgentRunResult.failed("TOOL_BUDGET_EXCEEDED",
                "工具调用预算 " + limits.maxToolCalls() + " 次会用尽（基线固定流程还需要下一次取证）",
                new ArrayList<>(evidence.values()), toolCalls, 0, 0);
    }

    /** 工具调用前的权限重校验：任何一条"已被撤销"的理由 → `CANCELLED(PERMISSION_REVOKED)`。 */
    private AgentRunResult revokedIfAny(ToolContext ctx, Map<String, AgentEvidence> evidence, int toolCalls) {
        List<String> reasons = permissionRecheck.revokedReasons(ctx);
        if (reasons.isEmpty()) {
            return null;
        }
        return AgentRunResult.cancelled("PERMISSION_REVOKED",
                "运行中权限被撤销：" + String.join("；", reasons),
                new ArrayList<>(evidence.values()), toolCalls, 0, 0);
    }

    private AgentRunResult toolFailure(ToolOutcome outcome, Map<String, AgentEvidence> evidence, int toolCalls) {
        return AgentRunResult.failed(outcome.errorCode(), outcome.errorMessage(),
                new ArrayList<>(evidence.values()), toolCalls, 0, 0);
    }

    /**
     * 选择要引用的事实编号：**未知事实只有在类型"允许未知"时才允许引用**（§3.1）。
     *
     * <p>基线不能"把查到的都引用上"——`order.alert_count` 这类"无来源"的未知事实在
     * `ORDER_STATUS` / `REASSIGN_HISTORY` 下不允许未知，引用它会让报告被同一校验器判无效
     * （本轮实测：三条用例因此红）。这是条件分支，不是弱化：它做的正是模型也必须做的取舍。
     */
    private static List<String> citableEvidenceIds(AgentProblemType problemType,
                                                   Map<String, AgentEvidence> evidence) {
        if (problemType == AgentProblemType.UNSUPPORTED) {
            // §3.1：UNSUPPORTED 的证据与建议都必须是空数组。
            // 入口预读会先登记主工单事实，所以这里**必须显式清空**，不能"把查到的都引用上"。
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, AgentEvidence> entry : evidence.entrySet()) {
            AgentEvidence item = entry.getValue();
            boolean citable = !item.unknown() || problemType.allowedUnknownFacts().contains(item.fact());
            if (citable) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }
}
