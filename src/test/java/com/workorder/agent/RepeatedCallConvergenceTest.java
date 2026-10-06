package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.support.StubModelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复调用收敛（设计稿 `docs/agent-design/AGENT-DESIGN.md` L133/L137）：
 * ① 同工具 + **规范化参数**命中**本轮快照缓存**（不重复执行，但**仍计工具预算**）；
 * ② 同 `callId` 不同参数 = 协议错误，**不执行该轮任何工具**；
 * ③ 连续两轮无新证据 → `FAILED(NO_PROGRESS)`（D84 裁决"两轮"）。
 */
@DisplayName("重复调用收敛：快照缓存 / 协议错误 / NO_PROGRESS")
class RepeatedCallConvergenceTest {

    private static final ToolContext CTX = ToolContext.ofDepartment("inv-cache-1", "42", "7");

    private StubModelServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    /** 计数桩：记录真实执行次数，返回足以支撑 ORDER_STATUS 的三条事实。 */
    private static final class CountingTool implements AgentTool {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public String name() {
            return "count_tool";
        }

        @Override
        public String description() {
            return "S2 测试用：可计数的虚构工具";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "orderNo", Map.of("type", "string"),
                    "note", Map.of("type", "string")), "required", List.of("orderNo"));
        }

        @Override
        public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
            executions.incrementAndGet();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("order.exists", "true");
            facts.put("order.status", "IN_PROGRESS");
            facts.put("order.assignee", "a*");
            return ToolOutcome.ok(facts);
        }
    }

    private InvestigationAgent agentWith(CountingTool tool, StubModelServer.Reply... script) {
        stub = new StubModelServer(script);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 256 * 1024);
        return new InvestigationAgent(model, registry, AgentLimits.s1Defaults());
    }

    /** 直接给 `finish_report` 的**参数** JSON（不是整包响应——整包会被当成参数解析失败）。 */
    private static String finishOrderStatusArgs(List<String> evidenceIds) {
        return "{\"problemType\":\"ORDER_STATUS\",\"evidenceIds\":[\"" + String.join("\",\"", evidenceIds)
                + "\"],\"suggestionIds\":[\"CONTACT_ASSIGNEE\"]}";
    }

    @Test
    @DisplayName("同工具同参数第二次：工具只执行一次，但预算计两次")
    void identicalRepeatHitsCacheButStillCountsBudget() {
        CountingTool tool = new CountingTool();
        InvestigationAgent agent = agentWith(tool,
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", "count_tool", "{\"orderNo\":\"A\"}")),
                StubModelServer.json(StubModelServer.toolCallTurn("call_2", "count_tool", "{\"orderNo\":\"A\"}")),
                StubModelServer.json(StubModelServer.finishTurnRaw(finishOrderStatusArgs(List.of("E1", "E2", "E3")))));

        AgentRunResult result = agent.investigate(CTX, "工单 A 到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(1, tool.executions.get(), "同参数第二次必须走缓存，不再执行工具");
        assertEquals(2, result.toolCalls(), "命中缓存仍计工具预算（否则重复调用成了免费通道）");
        assertEquals(3, result.evidence().size(), "缓存命中不产生新证据编号");
    }

    @Test
    @DisplayName("参数键序不同但语义相同：视为同一调用（缓存命中）")
    void keyOrderDoesNotMatter() {
        CountingTool tool = new CountingTool();
        InvestigationAgent agent = agentWith(tool,
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", "count_tool",
                        "{\"orderNo\":\"A\",\"note\":\"x\"}")),
                StubModelServer.json(StubModelServer.toolCallTurn("call_2", "count_tool",
                        "{\"note\":\"x\",\"orderNo\":\"A\"}")),
                StubModelServer.json(StubModelServer.finishTurnRaw(finishOrderStatusArgs(List.of("E1", "E2", "E3")))));

        AgentRunResult result = agent.investigate(CTX, "工单 A 到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(1, tool.executions.get(), "JSON 键序不影响同一性（要规范化后再做键）");
    }

    @Test
    @DisplayName("同 callId 不同参数 → MODEL_PROTOCOL_ERROR，且该轮不执行任何工具")
    void duplicateCallIdWithDifferentArgumentsIsProtocolError() {
        CountingTool tool = new CountingTool();
        InvestigationAgent agent = agentWith(tool,
                StubModelServer.json(StubModelServer.toolCallsTurn(
                        new String[]{"dup", "dup"},
                        new String[]{"count_tool", "count_tool"},
                        new String[]{"{\"orderNo\":\"A\"}", "{\"orderNo\":\"B\"}"})));

        AgentRunResult result = agent.investigate(CTX, "工单 A 到哪一步了？");

        assertEquals(AgentStatus.FAILED, result.status());
        assertEquals("MODEL_PROTOCOL_ERROR", result.failure().code());
        assertNull(result.report());
        assertEquals(0, tool.executions.get(), "非法批次里「看起来合法」的那部分也不得执行");
    }

    @Test
    @DisplayName("连续两轮无新证据 → FAILED(NO_PROGRESS)，report 为 null")
    void twoRoundsWithoutNewEvidenceEndsWithNoProgress() {
        CountingTool tool = new CountingTool();
        InvestigationAgent agent = agentWith(tool,
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", "count_tool", "{\"orderNo\":\"A\"}")),
                StubModelServer.json(StubModelServer.toolCallTurn("call_2", "count_tool", "{\"orderNo\":\"A\"}")),
                StubModelServer.json(StubModelServer.toolCallTurn("call_3", "count_tool", "{\"orderNo\":\"A\"}")));

        AgentRunResult result = agent.investigate(CTX, "工单 A 到哪一步了？");

        assertEquals(AgentStatus.FAILED, result.status());
        assertEquals("NO_PROGRESS", result.failure().code(), "连续两轮零新证据必须收敛成终态");
        assertNull(result.report(), "非 COMPLETED 不得产出报告");
        assertEquals(1, tool.executions.get());
    }

    @Test
    @DisplayName("重复之后换了参数：正常执行、正常产生新证据（缓存不误伤）")
    void differentArgumentsAfterRepeatStillExecute() {
        CountingTool tool = new CountingTool();
        InvestigationAgent agent = agentWith(tool,
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", "count_tool", "{\"orderNo\":\"A\"}")),
                StubModelServer.json(StubModelServer.toolCallTurn("call_2", "count_tool", "{\"orderNo\":\"B\"}")),
                StubModelServer.json(StubModelServer.finishTurnRaw(finishOrderStatusArgs(List.of("E1", "E2", "E3")))));

        AgentRunResult result = agent.investigate(CTX, "工单 A 到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(2, tool.executions.get(), "换了参数就是新调用，必须真执行");
        assertEquals(6, result.evidence().size(), "两次调用各登记一套证据");
    }

}
