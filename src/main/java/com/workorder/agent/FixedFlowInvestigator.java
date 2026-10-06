package com.workorder.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.agent.tool.DeptComparisonTool;
import com.workorder.agent.tool.OrderFactsTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p><b>任务分类用透明规则</b>（关键词，首版不引入模型；§3.1 L118：若将来引入意图分类模型，
 * 必须把它的调用与耗时计入基线）。<b>固定取证顺序</b>：起点单事实 →（问题涉及超时/转手时）同部门对照；
 * 证据足够即停，不强制把工具都查一遍（§3.1 L113）。
 *
 * <p><b>本版未实现的手册条目</b>（还没有对应工具，登记为待办）：L110 的"早期一页"、L112 的"同提交人近期记录"；
 * L109 的 SLA 读取已由 {@link OrderFactsTool} 的 `order.sla_deadline` 覆盖。
 */
public final class FixedFlowInvestigator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ORDER_NO = Pattern.compile("WO-\\d{8}-\\d{5}");

    private final AgentToolRegistry tools;
    private final AgentLimits limits;

    public FixedFlowInvestigator(AgentToolRegistry tools, AgentLimits limits) {
        this.tools = tools;
        this.limits = limits;
    }

    public AgentRunResult investigate(ToolContext ctx, String question) {
        Map<String, AgentEvidence> evidence = new LinkedHashMap<>();
        int toolCalls = 0;

        String orderNo = extractOrderNo(question);
        AgentProblemType problemType = orderNo == null ? AgentProblemType.UNSUPPORTED : classify(question);

        if (problemType != AgentProblemType.UNSUPPORTED) {
            if (toolCalls + 1 > limits.maxToolCalls()) {
                return budgetExceeded(evidence, toolCalls);
            }
            ToolOutcome facts = call(ctx, OrderFactsTool.NAME, orderNo, evidence, toolCalls);
            toolCalls++;
            if (!facts.ok()) {
                return toolFailure(facts, evidence, toolCalls);
            }
            if (needsPeerComparison(problemType)) {
                if (toolCalls + 1 > limits.maxToolCalls()) {
                    return budgetExceeded(evidence, toolCalls);
                }
                ToolOutcome peer = call(ctx, DeptComparisonTool.NAME, orderNo, evidence, toolCalls);
                toolCalls++;
                if (!peer.ok()) {
                    return toolFailure(peer, evidence, toolCalls);
                }
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
        if (containsAny(q, "到哪一步", "进展", "状态", "到哪", "现在怎么样")) {
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

    private static String extractOrderNo(String question) {
        Matcher matcher = ORDER_NO.matcher(question == null ? "" : question);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * 固定建议（条件分支）：必须自己避开 §11-4 的**禁止项**——否则报告会被同一校验器判无效，
     * 那是"基线自己写错"，会被 S6 误读成"基线更弱"。
     */
    private static List<String> suggestionsFor(AgentProblemType problemType, Map<String, AgentEvidence> evidence) {
        return switch (problemType) {
            case ORDER_STATUS -> hasUsableAssignee(evidence)
                    ? List.of(AgentSuggestion.CONTACT_ASSIGNEE.name())
                    : List.of(AgentSuggestion.ESCALATE_TO_DEPT_ADMIN.name());
            case TIMEOUT_SITUATION -> List.of(AgentSuggestion.ESCALATE_TO_DEPT_ADMIN.name());
            case REASSIGN_HISTORY -> hasEmptyAcceptEvents(evidence)
                    ? List.of(AgentSuggestion.WAIT_FOR_CLAIM.name())
                    : List.of(AgentSuggestion.CONTACT_ASSIGNEE.name());
            case UNSUPPORTED -> List.of();
        };
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
        var arguments = MAPPER.createObjectNode().put("orderNo", orderNo);
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
