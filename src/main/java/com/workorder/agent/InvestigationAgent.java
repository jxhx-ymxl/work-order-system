package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 调查助手的最小闭环（S1）：模型请求工具 → 程序执行 → 回传结果 → 提交报告。
 *
 * <p>本类只做协议与判据，不碰业务：
 * <ul>
 *   <li>外层三个上限（工具次数 / 模型轮次 / 运行墙钟）见 {@link AgentLimits} 与 §3.4；</li>
 *   <li>证据编号由本类统一分配（{@code E1}、{@code E2}…），模型只能引用工具结果里给出的编号；</li>
 *   <li>{@code finish_report} 是终止动作，**不注册进工具表**（§3.2）；</li>
 *   <li>完成与否由 {@link #validateReport} 按事实覆盖度判定，不看模型怎么说。</li>
 * </ul>
 *
 * <p><b>本轮明确不做</b>（§6 的 S4）：不接业务库、不接前端、不做权限、不做并发名额与取消、
 * 不做分布式配额、不做新鲜度校验。
 */
public final class InvestigationAgent {

    /** 终止动作名。它不是业务工具，模型不能"查询"它。 */
    static final String FINISH_REPORT = "finish_report";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentModel model;
    private final AgentToolRegistry tools;
    private final AgentLimits limits;

    public InvestigationAgent(AgentModel model, AgentToolRegistry tools, AgentLimits limits) {
        this.model = model;
        this.tools = tools;
        this.limits = limits;
    }

    public AgentRunResult investigate(String question) {
        long startedAtMillis = System.currentTimeMillis();
        int toolCalls = 0;
        int modelRounds = 0;
        int reportSubmissions = 0;
        Map<String, AgentEvidence> evidence = new LinkedHashMap<>();

        List<JsonNode> transcript = new ArrayList<>();
        transcript.add(systemPrompt());
        transcript.add(textMessage("user", SensitiveDataRedactor.redactText(question == null ? "" : question)));

        while (true) {
            Duration remainingBudget = limits.runBudget()
                    .minusMillis(System.currentTimeMillis() - startedAtMillis);
            if (remainingBudget.toMillis() < 1) {
                return AgentRunResult.timedOut("RUN_BUDGET_EXCEEDED",
                        "运行预算 " + limits.runBudget().toMillis() + "ms 已耗尽",
                        snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
            }
            if (modelRounds >= limits.maxModelRounds()) {
                return AgentRunResult.failed("ROUND_LIMIT_EXCEEDED",
                        "模型轮次上限 " + limits.maxModelRounds() + " 已用完",
                        snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
            }
            modelRounds++;

            // 单轮的读取上限不得大于剩余预算：否则 8 轮 × 30s 会把 §4.1 的 60s 超时链拉成 240s（复核发现 4）
            boolean budgetBound = remainingBudget.compareTo(limits.modelReadTimeout()) < 0;
            Duration roundReadTimeout = budgetBound ? remainingBudget : limits.modelReadTimeout();

            ModelTurn turn;
            try {
                turn = model.respond(transcript, roundReadTimeout);
            } catch (AgentModelException e) {
                if (budgetBound && "MODEL_TIMEOUT".equals(e.code())) {
                    return AgentRunResult.timedOut("RUN_BUDGET_EXCEEDED",
                            "运行预算 " + limits.runBudget().toMillis() + "ms 已耗尽（本轮读取按剩余预算收敛到 "
                                    + roundReadTimeout.toMillis() + "ms）",
                            snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
                }
                return onModelFailure(e, evidence, toolCalls, modelRounds, reportSubmissions);
            }
            transcript.add(turn.rawMessage());

            Optional<ModelToolCall> finish = turn.toolCalls().stream()
                    .filter(call -> FINISH_REPORT.equals(call.name()))
                    .findFirst();
            if (finish.isPresent()) {
                if (turn.toolCalls().size() != 1) {
                    return AgentRunResult.failed("MODEL_PROTOCOL_ERROR",
                            FINISH_REPORT + " 必须单独一轮提交，本轮还带了其它工具调用",
                            snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
                }
                reportSubmissions++;
                List<String> problems = validateReport(finish.get(), evidence);
                if (problems.isEmpty()) {
                    return AgentRunResult.completed(toReport(finish.get()), snapshot(evidence),
                            toolCalls, modelRounds, reportSubmissions);
                }
                if (reportSubmissions < limits.maxReportSubmissions()) {
                    // 把"缺什么"回传，允许重交一次；缺口清单里只说编号与事实键，不重放下发内容（§4.4）
                    // **必须作为 finish_report 的 tool 应答回传**：assistant 那条消息带 tool_calls:[call_finish]，
                    // 少了配对的 tool 消息，真供应商会直接 400（复核发现 1）。
                    transcript.add(toolMessage(finish.get().callId(), reportGapContent(problems)));
                    continue;
                }
                return AgentRunResult.failed("REPORT_INVALID",
                        "报告两次校验均不通过：" + String.join("；", problems),
                        snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
            }

            if (turn.toolCalls().isEmpty()) {
                return AgentRunResult.failed("MODEL_PROTOCOL_ERROR",
                        "本轮既没有工具调用也没有 " + FINISH_REPORT,
                        snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
            }
            if (toolCalls + turn.toolCalls().size() > limits.maxToolCalls()) {
                return AgentRunResult.failed("TOOL_BUDGET_EXCEEDED",
                        "工具调用预算 " + limits.maxToolCalls() + " 次会被本轮 "
                                + turn.toolCalls().size() + " 次请求超出",
                        snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
            }

            for (ModelToolCall call : turn.toolCalls()) {
                toolCalls++;   // 非法工具与坏参数照常计入预算——否则报错重试成了免费通道（§3.4）
                ToolOutcome outcome = tools.execute(call);
                transcript.add(toolMessage(call.callId(), toolResultContent(call, outcome, evidence)));
            }
        }
    }

    // ---------- 报告校验 ----------

    /**
     * 按事实覆盖度校验报告（§3.1 / §3.2）。返回缺口清单，空表示通过。
     *
     * <p>判据只看"报告引用的证据编号 → 事实键"这条链，不看模型写了什么话。
     */
    private List<String> validateReport(ModelToolCall finish, Map<String, AgentEvidence> evidence) {
        List<String> problems = new ArrayList<>();
        JsonNode arguments = finish.arguments();
        String declaredType = arguments.path("problemType").asText("");
        AgentProblemType problemType = AgentProblemType.parse(declaredType);
        if (problemType == null) {
            problems.add("problemType 不在首版支持列表：" + (declaredType.isBlank() ? "（缺失）" : declaredType));
            return problems;
        }

        List<String> evidenceIds = stringList(arguments.path("evidenceIds"));
        List<String> suggestionIds = stringList(arguments.path("suggestionIds"));
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
                // 允不允许之外的事实被判未知，等于把"不知道"写成结论，必须判未完成。
                if (cited.unknown() && !problemType.allowedUnknownFacts().contains(cited.fact())) {
                    problems.add("事实被判为未知且该类型不允许未知：" + cited.fact());
                }
            }
        }
        for (String required : problemType.requiredFacts()) {
            if (!coveredFacts.contains(required)) {
                problems.add("必需事实未被证据覆盖：" + required);
            }
        }
        return problems;
    }

    private AgentReport toReport(ModelToolCall finish) {
        JsonNode arguments = finish.arguments();
        return new AgentReport(
                AgentProblemType.parse(arguments.path("problemType").asText("")),
                stringList(arguments.path("evidenceIds")),
                stringList(arguments.path("suggestionIds")));
    }

    /** 报告校验缺口的内容：与工具错误同形状，由模型当作 {@code finish_report} 的返回值读取。 */
    private static ObjectNode reportGapContent(List<String> problems) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tool", FINISH_REPORT);
        node.put("ok", false);
        node.put("errorCode", "REPORT_INVALID");
        node.put("message", "报告校验不通过，缺口如下：" + String.join("；", problems)
                + "。请补齐后重新提交 " + FINISH_REPORT + "（不得编造编号）。");
        return node;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asText(""));
            }
        }
        return values;
    }

    // ---------- 工具结果 → transcript ----------

    private ObjectNode toolResultContent(ModelToolCall call, ToolOutcome outcome, Map<String, AgentEvidence> evidence) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tool", call.name());
        node.put("ok", outcome.ok());
        if (!outcome.ok()) {
            node.put("errorCode", outcome.errorCode());
            node.put("message", SensitiveDataRedactor.redactText(outcome.errorMessage()));
            return node;
        }
        ObjectNode facts = node.putObject("facts");
        ObjectNode evidenceIds = node.putObject("evidenceIds");
        ArrayNode unknownFacts = node.putArray("unknownFacts");
        for (Map.Entry<String, String> entry : outcome.facts().entrySet()) {
            String fact = entry.getKey();
            String id = "E" + (evidence.size() + 1);
            String value = SensitiveDataRedactor.redactText(entry.getValue());
            evidence.put(id, new AgentEvidence(id, fact, value, outcome.unknownFacts().contains(fact)));
            facts.put(fact, value);
            evidenceIds.put(fact, id);
        }
        outcome.unknownFacts().forEach(unknownFacts::add);
        return node;
    }

    private AgentRunResult onModelFailure(AgentModelException e, Map<String, AgentEvidence> evidence,
                                          int toolCalls, int modelRounds, int reportSubmissions) {
        String message = SensitiveDataRedactor.redactText(String.valueOf(e.getMessage()));
        if ("MODEL_TIMEOUT".equals(e.code())) {
            return AgentRunResult.timedOut(e.code(), message, snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
        }
        return AgentRunResult.failed(e.code(), message, snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
    }

    private static List<AgentEvidence> snapshot(Map<String, AgentEvidence> evidence) {
        return new ArrayList<>(evidence.values());
    }

    // ---------- 消息构造 ----------

    private JsonNode systemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是高校后勤工单的调查助手。你只能基于工具返回的事实作答，不得凭常识补全或推测。\n");
        prompt.append("【首版支持的问题类型】\n");
        for (AgentProblemType type : AgentProblemType.values()) {
            prompt.append("- ").append(type.name()).append("（").append(type.description()).append("）\n");
            if (!type.requiredFacts().isEmpty()) {
                prompt.append("  必需事实：").append(String.join("、", type.requiredFacts())).append("\n");
            }
            if (!type.allowedUnknownFacts().isEmpty()) {
                prompt.append("  允许未知（未知也算已覆盖，但必须说明）：")
                        .append(String.join("、", type.allowedUnknownFacts())).append("\n");
            }
            prompt.append("  前提：").append(type.premise()).append("\n");
        }
        prompt.append("【收尾方式】调查必须用 ").append(FINISH_REPORT)
                .append(" 结束，参数只有 problemType / evidenceIds / suggestionIds；"
                        + "证据编号只能引用工具结果 evidenceIds 里出现过的编号，不得编造；"
                        + "同一轮不要把它和其它工具调用混在一起。"
                        + "问题不属于上面前三类时，用 UNSUPPORTED 收尾并把两个数组都留空。\n");
        prompt.append("【可用工具】").append(String.join("、", tools.names())).append("\n");
        prompt.append("【建议编号目录】");
        for (AgentSuggestion suggestion : AgentSuggestion.values()) {
            prompt.append(suggestion.name()).append("（").append(suggestion.text()).append("）");
        }
        return textMessage("system", prompt.toString());
    }

    private static JsonNode textMessage(String role, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static JsonNode toolMessage(String callId, JsonNode content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", "tool");
        node.put("tool_call_id", callId);
        node.put("content", content.toString());
        return node;
    }
}
