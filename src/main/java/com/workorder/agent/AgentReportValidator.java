package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **报告校验器**（§3.1 完成判据 + §3.2 校验项）——agent 循环与**强固定流程基线**共用同一份。
 *
 * <p>校验项：① 引用的证据 / 建议编号真实存在；② 必需事实被证据覆盖（含 D82 的条件必需事实：
 * 证据证明 `order.exists=false` 时收缩为 `{order.exists}`）；③ 允许未知项之外的事实不得标未知；
 * ④ §11-4 的**禁止项**（没有联系依据时不得建议联系处理人）；⑤ `UNSUPPORTED` 必须两个数组都为空。
 *
 * <p>抽成独立类是因为 §3.1（`AGENT-LEARNING-EVAL.md`）要求基线与 agent **用同一校验器**——
 * 两套校验器会让 S6 的比较失去意义。
 */
final class AgentReportValidator {

    private AgentReportValidator() {
    }

    /** agent 入口：从 `finish_report` 的工具调用参数里取出三个字段再校验。 */
    static List<String> validate(ModelToolCall finish, Map<String, AgentEvidence> evidence) {
        List<String> problems = new ArrayList<>();
        JsonNode arguments = finish.arguments();
        String declaredType = arguments.path("problemType").asText("");
        AgentProblemType problemType = AgentProblemType.parse(declaredType);
        if (problemType == null) {
            problems.add("problemType 不在首版支持列表：" + (declaredType.isBlank() ? "（缺失）" : declaredType));
            return problems;
        }
        return validate(problemType, stringList(arguments.path("evidenceIds")),
                stringList(arguments.path("suggestionIds")), evidence);
    }

    /** 基线入口：三个字段已经是结构化值。 */
    static List<String> validate(AgentProblemType problemType, List<String> evidenceIds,
                                 List<String> suggestionIds, Map<String, AgentEvidence> evidence) {
        List<String> problems = new ArrayList<>();
        for (String id : evidenceIds) {
            if (!evidence.containsKey(id)) {
                problems.add("证据编号不存在：" + id);
            }
        }
        for (String id : suggestionIds) {
            if (!AgentSuggestion.isKnown(id)) {
                problems.add("建议编号不存在：" + id);
            }
        }

        if (problemType == AgentProblemType.UNSUPPORTED) {
            if (!evidenceIds.isEmpty() || !suggestionIds.isEmpty()) {
                problems.add("UNSUPPORTED 必须提交空的证据与建议编号");
            }
            return problems;
        }

        Set<String> coveredFacts = new LinkedHashSet<>();
        for (String id : evidenceIds) {
            AgentEvidence cited = evidence.get(id);
            if (cited != null) {
                coveredFacts.add(cited.fact());
                // "未知也算覆盖"只对 **允许未知** 的事实成立（§3.1 的那一列）。
                // 允许之外的事实被判未知，等于把"不知道"写成结论，必须判未完成。
                if (cited.unknown() && !problemType.allowedUnknownFacts().contains(cited.fact())) {
                    problems.add("事实被判为未知且该类型不允许未知：" + cited.fact());
                }
            }
        }
        // §3.1 条件必需事实（D82）：证据能证明 order.exists=false 时，必需事实收缩为 {order.exists}。
        boolean orderMissing = evidenceIds.stream()
                .map(evidence::get)
                .anyMatch(cited -> cited != null
                        && "order.exists".equals(cited.fact())
                        && "false".equals(cited.value()));
        Set<String> requiredFacts = orderMissing ? Set.of("order.exists") : problemType.requiredFacts();
        for (String required : requiredFacts) {
            if (!coveredFacts.contains(required)) {
                problems.add("必需事实未被证据覆盖：" + required);
            }
        }
        checkProhibitedSuggestions(problemType, suggestionIds, evidence, problems);
        return problems;
    }

    /**
     * §3.1 的**禁止项**（§11-4 裁决：只做禁止项，不做"必须建议 X"的强制项；失败码复用 `REPORT_INVALID`）。
     *
     * <p>规则：当"能不能联系到处理人"本身没有依据时，不得给出"联系当前处理人"的建议。
     * <ul>
     *   <li>`ORDER_STATUS`：`order.assignee` 未知或为空时不得含 `CONTACT_ASSIGNEE`；</li>
     *   <li>`REASSIGN_HISTORY`：`order.accept_events` 为空（从未接单）时同样不得含。</li>
     * </ul>
     */
    private static void checkProhibitedSuggestions(AgentProblemType problemType, List<String> suggestionIds,
                                                   Map<String, AgentEvidence> evidence, List<String> problems) {
        if (!suggestionIds.contains(AgentSuggestion.CONTACT_ASSIGNEE.name())) {
            return;
        }
        String blockingFact = switch (problemType) {
            case ORDER_STATUS -> "order.assignee";
            case REASSIGN_HISTORY -> "order.accept_events";
            default -> null;
        };
        if (blockingFact == null) {
            return;
        }
        boolean noBasis = evidence.values().stream()
                .anyMatch(cited -> blockingFact.equals(cited.fact()) && (cited.unknown() || cited.empty()));
        if (noBasis) {
            problems.add("禁止项：事实 " + blockingFact + " 未知或为空（没有联系依据）时，"
                    + "suggestionIds 不得含 " + AgentSuggestion.CONTACT_ASSIGNEE.name());
        }
    }

    /** 把 JSON 数组读成字符串列表（`toReport` 也用它）。 */
    static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asText(""));
            }
        }
        return values;
    }
}
