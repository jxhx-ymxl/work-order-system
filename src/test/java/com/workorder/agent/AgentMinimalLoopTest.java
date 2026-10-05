package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.StubOrderLogsTool;
import com.workorder.agent.tool.StubOrderSnapshotTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * S1 最小闭环的行为测试（`docs/AGENT-PLAN.md` §6 的 S1 通过判据）。
 *
 * <p>每个用例都从**公开接口** {@code investigate(question)} 出发，走真实 HTTP + 真实 JSON 解析；
 * 桩只替换模型（远端边界），不替换被测方自己的任何内部协作者。
 */
@DisplayName("调查助手最小闭环（虚构工具 + 本地 HTTP 桩）")
class AgentMinimalLoopTest {

    private static final String ORDER_NO = "WO-20260607-00001";

    private StubModelServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private InvestigationAgent startAgent(AgentLimits limits, StubModelServer.Reply... script) {
        return startAgent(limits,
                List.of(new StubOrderSnapshotTool(), new StubOrderLogsTool()), script);
    }

    private InvestigationAgent startAgent(AgentLimits limits, List<AgentTool> tools, StubModelServer.Reply... script) {
        stub = new StubModelServer(script);
        AgentToolRegistry registry = new AgentToolRegistry(tools);
        AgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        return new InvestigationAgent(model, registry, limits);
    }

    private static String snapshotArgs() {
        return "{\"orderNo\":\"" + ORDER_NO + "\"}";
    }

    @Test
    @DisplayName("模型请求工具 → 程序执行 → 回传结果 → 提交报告：闭环完成，报告只含编号")
    void toolCallThenFinishReport_completes() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallTurn("call_a", StubOrderSnapshotTool.NAME, snapshotArgs())),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("CONTACT_ASSIGNEE"))));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 现在到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(),
                () -> "failure=" + result.failure() + " " + fingerprint());
        assertNull(result.failure(), "成功路径不应带失败原因");
        assertNotNull(result.report());
        assertEquals(AgentProblemType.ORDER_STATUS, result.report().problemType());
        assertEquals(List.of("E1", "E2", "E3"), result.report().evidenceIds());
        assertEquals(List.of("CONTACT_ASSIGNEE"), result.report().suggestionIds());
        assertEquals(1, result.toolCalls(), "一次工具调用");
        assertEquals(2, result.modelRounds(), "两轮：请求工具 + 提交报告");
        assertEquals(1, result.reportSubmissions());

        // 证据编号是后端分配的，且与事实键绑定
        assertEquals("order.exists", result.evidence().get(0).fact());
        assertEquals("order.status", result.evidence().get(1).fact());

        // 回传给模型的 transcript 里，工具结果必须按调用 ID 配对，并带上模型可引用的证据编号
        JsonNode secondRequest = stub.received(1);
        List<JsonNode> toolMessages = StubModelServer.toolMessages(secondRequest);
        assertEquals(1, toolMessages.size(), "第二轮请求里应有一条工具结果");
        assertEquals("call_a", toolMessages.get(0).path("tool_call_id").asText());
        String toolContent = toolMessages.get(0).path("content").asText();
        assertTrue(toolContent.contains("\"order.status\":\"IN_PROGRESS\""), toolContent);
        assertTrue(toolContent.contains("\"order.status\":\"E2\""), toolContent);
    }

    @Test
    @DisplayName("同一轮两个工具调用：结果按调用 ID 逐一回传")
    void twoToolCallsInOneTurn_pairByCallId() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallsTurn(
                        new String[]{"call_snap", "call_logs"},
                        new String[]{StubOrderSnapshotTool.NAME, StubOrderLogsTool.NAME},
                        new String[]{snapshotArgs(), snapshotArgs()})),
                StubModelServer.json(StubModelServer.finishTurn("REASSIGN_HISTORY",
                        List.of("E6", "E7"), List.of("WAIT_FOR_CLAIM"))));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 被谁处理过？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure() + " " + fingerprint());
        assertEquals(2, result.toolCalls(), "一轮里的两个调用都要执行");
        assertEquals(7, result.evidence().size(), "snapshot 5 条 + logs 2 条");

        List<JsonNode> toolMessages = StubModelServer.toolMessages(stub.received(1));
        assertEquals(2, toolMessages.size());
        assertEquals("call_snap", toolMessages.get(0).path("tool_call_id").asText());
        assertEquals("call_logs", toolMessages.get(1).path("tool_call_id").asText());
        assertTrue(toolMessages.get(0).path("content").asText().contains("stub_order_snapshot"));
        assertTrue(toolMessages.get(1).path("content").asText().contains("order.accept_events"));
    }

    @Test
    @DisplayName("非法工具：回传 UNKNOWN_TOOL 错误结果（不抛异常），模型改用合法工具后仍能完成")
    void unknownTool_returnsErrorResultAndRunContinues() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallTurn("call_x", "no_such_tool", "{}")),
                StubModelServer.json(StubModelServer.toolCallTurn("call_a", StubOrderSnapshotTool.NAME, snapshotArgs())),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("CONTACT_ASSIGNEE"))));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 的进展？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure() + " " + fingerprint());
        assertEquals(2, result.toolCalls(), "非法工具也算一次调用");

        JsonNode firstToolMessage = StubModelServer.toolMessages(stub.received(1)).get(0);
        assertEquals("call_x", firstToolMessage.path("tool_call_id").asText());
        assertTrue(firstToolMessage.path("content").asText().contains("\"errorCode\":\"UNKNOWN_TOOL\""),
                firstToolMessage.path("content").asText());
    }

    @Test
    @DisplayName("坏参数：size 超上限 → BAD_ARGUMENT，且照常计入预算")
    void badArgument_returnsBadArgumentAndCountsAgainstBudget() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallTurn("call_bad", StubOrderLogsTool.NAME,
                        "{\"orderNo\":\"" + ORDER_NO + "\",\"size\":999}")),
                StubModelServer.json(StubModelServer.toolCallTurn("call_ok", StubOrderLogsTool.NAME,
                        "{\"orderNo\":\"" + ORDER_NO + "\",\"size\":20}")),
                StubModelServer.json(StubModelServer.finishTurn("REASSIGN_HISTORY",
                        List.of("E1", "E2"), List.of("WAIT_FOR_CLAIM"))));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 转过几手？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure() + " " + fingerprint());
        assertEquals(2, result.toolCalls());
        JsonNode badToolMessage = StubModelServer.toolMessages(stub.received(1)).get(0);
        assertTrue(badToolMessage.path("content").asText().contains("\"errorCode\":\"BAD_ARGUMENT\""),
                badToolMessage.path("content").asText());
    }

    @Test
    @DisplayName("报告引用了不存在的编号：把缺口回传后重试一次，仍不过 → FAILED(REPORT_INVALID) 且无报告")
    void reportWithUnknownEvidenceId_failsAfterOneRetry() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS", List.of("E99"), List.of("CONTACT_ASSIGNEE"))),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS", List.of("E99"), List.of("CONTACT_ASSIGNEE"))));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "REPORT_INVALID");
        assertEquals(2, result.reportSubmissions(), "首次 + 一次重试");
        assertEquals(2, result.modelRounds());
        String retryPrompt = StubModelServer.lastMessage(stub.received(1)).path("content").asText();
        assertTrue(retryPrompt.contains("E99"), "重试提示要点名不存在的编号：" + retryPrompt);
    }

    @Test
    @DisplayName("报告缺少必需事实：缺口回传后重交即通过（完成判据只看证据覆盖）")
    void reportMissingRequiredFact_retryFixesIt() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallTurn("call_a", StubOrderSnapshotTool.NAME, snapshotArgs())),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS", List.of("E1"), List.of())),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("ESCALATE_TO_DEPT_ADMIN"))));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure() + " " + fingerprint());
        assertEquals(2, result.reportSubmissions(), "第一次不过、第二次通过");
        assertEquals(3, result.modelRounds());
        String retryPrompt = StubModelServer.lastMessage(stub.received(2)).path("content").asText();
        assertTrue(retryPrompt.contains("order.assignee"), "缺口清单要点名缺的事实：" + retryPrompt);
    }

    @Test
    @DisplayName("模型在报告里写结论但不引证据：仍判未完成（判据落在证据编号上，不落在模型的说法上）")
    void modelNarrativeWithoutEvidence_doesNotCount() {
        String narrative = "{\"problemType\":\"ORDER_STATUS\",\"evidenceIds\":[],\"suggestionIds\":[],"
                + "\"summary\":\"已确认工单处于处理中，处理人是张伟\"}";
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.finishTurnRaw(narrative)),
                StubModelServer.json(StubModelServer.finishTurnRaw(narrative)));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "REPORT_INVALID");
    }

    private static void assertTerminalWithoutReport(AgentRunResult result, AgentStatus status, String code) {
        assertEquals(status, result.status(), () -> "failure=" + result.failure());
        assertNotNull(result.failure());
        assertEquals(code, result.failure().code());
        assertNull(result.report(), "失败/超时不得产出报告（失败不得被伪装成正常报告）");
    }

    @Test
    @DisplayName("模型响应永不结束：读取过程中即中止并释放连接 → FAILED(RESPONSE_TOO_LARGE)")
    void endlessResponse_abortsDuringRead() {
        StubModelServer.EndlessReply endless = StubModelServer.endless(4096);
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults().withMaxModelResponseBytes(8192), endless);

        AgentRunResult result = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> agent.investigate("工单 " + ORDER_NO + " 到哪一步了？"));

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "RESPONSE_TOO_LARGE");
        // 桩观测到的写入量是"客户端有没有一边读一边断"的直接证据：
        // 读满上限就断开 → 服务端写不进太多；读完整响应再判大小 → 这里会挂死（用例跑不完）。
        assertTrue(endless.written() >= 8192, "服务端至少要写出超过上限的量，说明客户端确实在读：" + endless.written());
        assertTrue(endless.written() < 1024 * 1024, "客户端读满即断开，服务端不会无限写下去：" + endless.written());
    }

    @Test
    @DisplayName("模型读取超时：TIMED_OUT(MODEL_TIMEOUT)，不产出报告")
    void slowModel_timesOut() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults().withModelReadTimeout(Duration.ofMillis(200)),
                StubModelServer.delayed(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of()), 1500));

        AgentRunResult result = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> agent.investigate("帮我看看这个工单"));

        assertTerminalWithoutReport(result, AgentStatus.TIMED_OUT, "MODEL_TIMEOUT");
    }

    @Test
    @DisplayName("工具预算超限：FAILED(TOOL_BUDGET_EXCEEDED)，不产出报告")
    void toolBudgetExceeded_fails() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults().withMaxToolCalls(1),
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", StubOrderSnapshotTool.NAME, snapshotArgs())),
                StubModelServer.json(StubModelServer.toolCallTurn("call_2", StubOrderSnapshotTool.NAME, snapshotArgs())));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "TOOL_BUDGET_EXCEEDED");
        assertEquals(2, stub.requestCount(), "第二轮被预算拦下，不应再向模型发请求");
    }

    @Test
    @DisplayName("模型轮次上限：FAILED(ROUND_LIMIT_EXCEEDED)，调查不会无限进行")
    void modelRoundLimit_fails() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults().withMaxModelRounds(2),
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", StubOrderSnapshotTool.NAME, snapshotArgs())),
                StubModelServer.json(StubModelServer.toolCallTurn("call_2", StubOrderSnapshotTool.NAME, snapshotArgs())));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "ROUND_LIMIT_EXCEEDED");
        assertEquals(2, stub.requestCount());
    }

    @Test
    @DisplayName("模型只说话、既不调工具也不提交报告：FAILED(MODEL_PROTOCOL_ERROR)")
    void contentOnlyTurn_isProtocolError() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.contentOnlyTurn("我觉得应该没问题")));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "MODEL_PROTOCOL_ERROR");
    }

    @Test
    @DisplayName("finish_report 与其它工具调用混在一轮：FAILED(MODEL_PROTOCOL_ERROR)")
    void finishReportMixedWithToolCalls_isProtocolError() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallsTurn(
                        new String[]{"call_a", "call_finish"},
                        new String[]{StubOrderSnapshotTool.NAME, "finish_report"},
                        new String[]{snapshotArgs(), "{\"problemType\":\"UNSUPPORTED\",\"evidenceIds\":[],\"suggestionIds\":[]}"})));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "MODEL_PROTOCOL_ERROR");
    }

    @Test
    @DisplayName("模型返回非 2xx：FAILED(MODEL_HTTP_ERROR)，不产出报告")
    void modelHttpError_fails() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.httpError(401, "invalid api key"));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "MODEL_HTTP_ERROR");
    }

    @Test
    @DisplayName("模型返回的响应没有 choices：FAILED(MODEL_PROTOCOL_ERROR)")
    void malformedResponse_isProtocolError() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.malformedTurn()));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "MODEL_PROTOCOL_ERROR");
    }

    @Test
    @DisplayName("问题里的手机号/邮箱在外发前被脱敏；不属于支持范围时按 UNSUPPORTED 收尾")
    void questionIsRedactedBeforeLeavingTheProcess() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));

        AgentRunResult result = agent.investigate(
                "工单 " + ORDER_NO + " 是我报的，手机号 13812345678，邮箱 zhangsan@example.com，帮我看看该找谁");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(AgentProblemType.UNSUPPORTED, result.report().problemType());
        assertTrue(result.report().evidenceIds().isEmpty(), "不支持的问题不得给事实性结论");
        assertTrue(result.report().suggestionIds().isEmpty());

        String sent = stub.received(0).toString();
        assertFalse(sent.contains("13812345678"), "手机号不得出网");
        assertFalse(sent.contains("zhangsan@example.com"), "邮箱不得出网");
        assertTrue(sent.contains("138****5678"), "应为掩码后的手机号");
        assertTrue(sent.contains("z***@example.com"), "应为掩码后的邮箱");
    }

    @Test
    @DisplayName("允许未知的事实（告警记录源不可用）：未知也算覆盖，完成后证据带 unknown 标记")
    void allowedUnknownFact_stillCompletes() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallTurn("call_a", StubOrderSnapshotTool.NAME,
                        "{\"orderNo\":\"" + StubOrderSnapshotTool.NO_ALERT_SOURCE_ORDER_NO + "\"}")),
                StubModelServer.json(StubModelServer.finishTurn("TIMEOUT_REASON",
                        List.of("E1", "E2", "E3", "E4", "E5"), List.of())));

        AgentRunResult result = agent.investigate("这张单为什么超时？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure() + " " + fingerprint());
        AgentEvidence alertCount = result.evidence().stream()
                .filter(e -> "order.alert_count".equals(e.fact())).findFirst().orElseThrow();
        assertTrue(alertCount.unknown(), "来源不可用时必须显式标未知，而不是留空或编一个数");
    }

    @Test
    @DisplayName("不允许未知的事实缺失（日志源不可用）：判失败，不编造“从未接单”")
    void notAllowedUnknownFact_failsInsteadOfFabricating() {
        String finish = "{\"problemType\":\"REASSIGN_HISTORY\",\"evidenceIds\":[\"E1\",\"E2\"],"
                + "\"suggestionIds\":[\"WAIT_FOR_CLAIM\"]}";
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallTurn("call_logs", StubOrderLogsTool.NAME,
                        "{\"orderNo\":\"" + StubOrderLogsTool.NO_LOG_SOURCE_ORDER_NO + "\"}")),
                StubModelServer.json(StubModelServer.finishTurnRaw(finish)),
                StubModelServer.json(StubModelServer.finishTurnRaw(finish)));

        AgentRunResult result = agent.investigate("这张单被谁处理过？");

        assertTerminalWithoutReport(result, AgentStatus.FAILED, "REPORT_INVALID");
        String retryPrompt = StubModelServer.lastMessage(stub.received(2)).path("content").asText();
        assertTrue(retryPrompt.contains("order.accept_events"), "缺口清单要点名该事实：" + retryPrompt);
    }

    /** 断言失败时打印的排障指纹：每次请求的消息条数、工具结果条数与最后一条消息。 */
    private String fingerprint() {
        StringBuilder sb = new StringBuilder("stubRequests=").append(stub.requestCount()).append(" [");
        for (int i = 0; i < stub.requestCount(); i++) {
            JsonNode request = stub.received(i);
            sb.append(i).append(":msgs=").append(request.path("messages").size())
                    .append(",tools=").append(StubModelServer.toolMessages(request).size())
                    .append(",last=").append(request.path("messages").path(request.path("messages").size() - 1)
                            .path("content").asText("")).append(" ");
        }
        return sb.append("]").toString();
    }

    @Test
    @DisplayName("报告校验失败：缺口清单用配对的 tool 消息回传（不配对则真供应商 400）")
    void reportValidationGap_isAnsweredAsPairedToolMessage() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.toolCallTurn("call_a", StubOrderSnapshotTool.NAME, snapshotArgs())),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS", List.of("E1"), List.of())),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of())));

        AgentRunResult result = agent.investigate("工单 " + ORDER_NO + " 到哪一步了？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure() + " " + fingerprint());
        assertTrue(stub.protocolViolations().isEmpty(), "桩不该看到未配对的 tool_call：" + stub.protocolViolations());

        // 提交 finish_report 的那条 assistant 消息，必须有一条 tool_call_id 相同的应答
        JsonNode gapMessage = StubModelServer.toolMessageFor(stub.received(2), "call_finish");
        assertFalse(gapMessage.isMissingNode(), "缺口清单必须作为 call_finish 的 tool 应答：" + fingerprint());
        assertTrue(gapMessage.path("content").asText().contains("order.assignee"),
                "缺口清单要点名缺的事实：" + gapMessage.path("content").asText());
        assertTrue(StubModelServer.lastMessage(stub.received(2)).path("role").asText().equals("tool"),
                "最后一条消息应是 tool 应答（不能再用 system 消息回复 tool_call）");
    }

    @Test
    @DisplayName("工具执行抛异常：转成 TOOL_FAILED 结果回传，不穿透、不产生假结论")
    void toolException_becomesToolFailedOutcome() {
        AgentTool exploding = new AgentTool() {
            @Override
            public String name() {
                return "stub_explode";
            }

            @Override
            public String description() {
                return "S1 测试用：执行即抛异常";
            }

            @Override
            public java.util.Map<String, Object> parameterSchema() {
                return java.util.Map.of("type", "object", "properties", java.util.Map.of(), "required", List.of());
            }

            @Override
            public ToolOutcome execute(JsonNode arguments) {
                throw new IllegalStateException("数据库连接失败（模拟）");
            }
        };
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                List.of(new StubOrderSnapshotTool(), exploding),
                StubModelServer.json(StubModelServer.toolCallTurn("call_boom", "stub_explode", "{}")),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));

        AgentRunResult result = agent.investigate("这张单是不是有问题？");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure() + " " + fingerprint());
        assertEquals(1, result.toolCalls());
        String toolContent = StubModelServer.toolMessageFor(stub.received(1), "call_boom").path("content").asText();
        assertTrue(toolContent.contains("\"errorCode\":\"TOOL_FAILED\""), toolContent);
        assertTrue(toolContent.contains("IllegalStateException"), "异常类型要能帮到排障：" + toolContent);
        assertTrue(stub.protocolViolations().isEmpty(), stub.protocolViolations().toString());
    }

    @Test
    @DisplayName("运行预算：单轮读取按剩余预算收敛，100ms 预算不会被配置的读超时拖长")
    void runBudgetClampsSingleRoundReadTimeout() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults()
                        .withRunBudget(Duration.ofMillis(100)),
                StubModelServer.delayed(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of()), 3000));

        long startedAtNanos = System.nanoTime();
        AgentRunResult result = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> agent.investigate("随便问问"));
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        assertTerminalWithoutReport(result, AgentStatus.TIMED_OUT, "RUN_BUDGET_EXCEEDED");
        assertTrue(elapsedMillis < 2500,
                "必须在预算量级内返回，而不是等满配置的 30s 读超时：实际 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("请求里带上工具定义（两个虚构工具），模型才能按名字调用")
    void toolDefinitionsAreSentToTheModel() {
        InvestigationAgent agent = startAgent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));

        agent.investigate("随便问问");

        JsonNode tools = stub.received(0).path("tools");
        assertEquals(2, tools.size(), tools.toString());
        List<String> names = new java.util.ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("function").path("name").asText()));
        assertTrue(names.contains(StubOrderSnapshotTool.NAME), names.toString());
        assertTrue(names.contains(StubOrderLogsTool.NAME), names.toString());
        assertTrue(tools.get(0).path("function").path("parameters").path("required").isArray(),
                "参数 schema 要带 required，模型才知道什么必填");
    }
}
