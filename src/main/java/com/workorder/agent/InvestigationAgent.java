package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workorder.agent.tool.OrderFactsTool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p><b>入口固定预读</b>（设计稿 L80）：起点单是**结构化入参**，入口先读一次主工单、注册 root 引用，
 * 不让模型"选择"这个必读动作；这一次**计入工具成本**（两组实验同口径）。问题文本只用于向模型提问。
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
    /** 入口预读用哪个工具读主工单；默认 {@link OrderFactsTool#NAME}（测试可用虚构工具替换）。 */
    private final String rootToolName;

    public InvestigationAgent(AgentModel model, AgentToolRegistry tools, AgentLimits limits) {
        this(model, tools, limits, OrderFactsTool.NAME);
    }

    public InvestigationAgent(AgentModel model, AgentToolRegistry tools, AgentLimits limits, String rootToolName) {
        this.model = model;
        this.tools = tools;
        this.limits = limits;
        this.rootToolName = rootToolName;
    }

    public AgentRunResult investigate(ToolContext ctx, String rootOrderNo, String question) {
        long startedAtMillis = System.currentTimeMillis();
        int toolCalls = 0;
        int modelRounds = 0;
        int reportSubmissions = 0;
        Map<String, AgentEvidence> evidence = new LinkedHashMap<>();
        // 本轮快照缓存（设计稿 L133）：键 = 工具名 + 规范化参数；仅存活于本次调查，不落库、不跨调查。
        Map<String, ObjectNode> snapshotCache = new LinkedHashMap<>();
        int staleRounds = 0;   // 连续"零新证据"轮数（D84：连续两轮 → NO_PROGRESS）

        // 入口固定预读（L80）：不花一次模型往返让它"选择"主工单，也不从问题文本里抠单号。
        // 这一次计成本，并写进快照缓存——模型再用相同参数查同一张单时命中的是预读结果（不重复执行/登记）。
        ModelToolCall rootCall = new ModelToolCall("preread-root", rootToolName,
                MAPPER.createObjectNode().put("orderNo", rootOrderNo));
        toolCalls++;
        ToolOutcome rootOutcome = tools.execute(ctx, rootCall);
        if (!rootOutcome.ok()) {
            return AgentRunResult.failed(rootOutcome.errorCode(),
                    SensitiveDataRedactor.redactText(rootOutcome.errorMessage()),
                    snapshot(evidence), toolCalls, 0, 0);
        }
        ObjectNode rootContent = toolResultContent(rootCall, rootOutcome, evidence);
        snapshotCache.put(cacheKey(rootCall), rootContent.deepCopy());

        List<JsonNode> transcript = new ArrayList<>();
        transcript.add(systemPrompt(rootCall, rootContent));
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
            transcript.add(turn.echoMessage());

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
                List<String> problems = AgentReportValidator.validate(finish.get(), evidence);
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

            // 同一轮里同一 callId 参数不一致 = 非法批次（设计稿 L133/L137）：**该轮任何工具都不执行**
            if (hasConflictingCallIds(turn.toolCalls())) {
                return AgentRunResult.failed("MODEL_PROTOCOL_ERROR",
                        "同一轮里同一 callId 对应不同参数：本轮任何工具都不执行",
                        snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
            }
            int evidenceBeforeRound = evidence.size();
            for (ModelToolCall call : turn.toolCalls()) {
                toolCalls++;   // 命中缓存也照常计入预算——否则重复调用成了绕过预算的免费通道（§3.4）
                String key = cacheKey(call);
                ObjectNode cached = snapshotCache.get(key);
                ObjectNode content;
                if (cached != null) {
                    content = cached.deepCopy();   // 复用上次结果（含原来的证据编号）：不执行工具、不登记新证据
                } else {
                    content = toolResultContent(call, tools.execute(ctx, call), evidence);
                    snapshotCache.put(key, content.deepCopy());
                }
                transcript.add(toolMessage(call.callId(), content));
            }
            if (evidence.size() == evidenceBeforeRound) {
                staleRounds++;
                if (staleRounds >= 2) {
                    return AgentRunResult.failed("NO_PROGRESS",
                            "连续 " + staleRounds + " 轮没有产生新证据（重复调用命中快照缓存，调查没有推进）",
                            snapshot(evidence), toolCalls, modelRounds, reportSubmissions);
                }
            } else {
                staleRounds = 0;
            }
        }
    }

    // ---------- 报告校验 ----------

    /**
     * 按事实覆盖度校验报告（§3.1 / §3.2）。返回缺口清单，空表示通过。
     *
     * <p>判据只看"报告引用的证据编号 → 事实键"这条链，不看模型写了什么话。
     */
    /**
     * 同一轮里同一 `callId` 出现且参数**不一致** → 非法批次（设计稿 L133/L137）：
     * 该轮**任何工具都不执行**，包括其中"看起来合法"的那些。
     */
    private static boolean hasConflictingCallIds(List<ModelToolCall> calls) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (ModelToolCall call : calls) {
            String canonical = canonicalArguments(call.arguments());
            String previous = seen.putIfAbsent(call.callId(), canonical);
            if (previous != null && !previous.equals(canonical)) {
                return true;
            }
        }
        return false;
    }

    /** 快照缓存键：工具名 + **规范化参数**（对象键序不影响同一性，数组顺序保留）。 */
    private static String cacheKey(ModelToolCall call) {
        return call.name() + "|" + canonicalArguments(call.arguments());
    }

    private static String canonicalArguments(JsonNode node) {
        StringBuilder out = new StringBuilder();
        writeCanonical(node, out);
        return out.toString();
    }

    private static void writeCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);            // 键序不影响同一性
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append('"').append(names.get(i)).append("\":");
                writeCanonical(node.get(names.get(i)), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCanonical(node.get(i), out);   // 数组顺序保留
            }
            out.append(']');
            return;
        }
        out.append(node.toString());        // 标量：JSON 文本形态稳定
    }

    // 报告校验（完成判据 / 允许未知 / 禁止项 / 条件必需事实）见 AgentReportValidator——
    // §3.1（AGENT-LEARNING-EVAL.md）要求基线与 agent 用**同一校验器**。

    private AgentReport toReport(ModelToolCall finish) {
        JsonNode arguments = finish.arguments();
        return new AgentReport(
                AgentProblemType.parse(arguments.path("problemType").asText("")),
                AgentReportValidator.stringList(arguments.path("evidenceIds")),
                AgentReportValidator.stringList(arguments.path("suggestionIds")));
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

    // 证据编号的分配已移到 EvidenceLedger.recordInto（基线与 agent 共用同一登记机制）。

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
        Map<String, String> newIds = EvidenceLedger.recordInto(evidence, outcome);
        newIds.forEach((fact, id) -> {
            facts.put(fact, evidence.get(id).value());
            evidenceIds.put(fact, id);
        });
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

    private JsonNode systemPrompt(ModelToolCall rootCall, ObjectNode rootContent) {
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
        // 入口预读的事实直接写进提示：模型可直接引用这些编号，不必再自己"选择"读主工单。
        prompt.append("【入口已预读的主工单】orderRef=")
                .append(rootCall.arguments().path("orderNo").asText())
                .append("（已登记，可直接在 evidenceIds 中引用下列编号）\n");
        JsonNode rootFacts = rootContent.path("facts");
        JsonNode rootEvidenceIds = rootContent.path("evidenceIds");
        rootEvidenceIds.fieldNames().forEachRemaining(fact -> prompt.append("  - ")
                .append(fact).append('=').append(rootFacts.path(fact).asText())
                .append(" (").append(rootEvidenceIds.path(fact).asText()).append(")\n"));
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
