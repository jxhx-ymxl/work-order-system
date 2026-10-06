package com.workorder.agent;

import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行中有界重试（槽 21）的判据：429 可恢复且**物理调用计数**可见；持续 429 用尽即失败；
 * 非 429 的 4xx **不重试**；等待**不得睡过运行预算**；重试**不绕过工具边界的权限重校验**。
 * 全部用本地 HTTP 桩，零真实费用。
 */
@DisplayName("运行中有界重试（槽 21）")
class ModelRetryTest {

    private static final String ORDER_NO = "WO-20261006-00021";

    private StubModelServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private InvestigationAgent agent(AgentLimits limits, PermissionRecheck recheck, StubModelServer.Reply... script) {
        stub = new StubModelServer(script);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new RootStubTool()));
        HttpAgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        return new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME, FinalReview.NONE, recheck);
    }

    /** 入口预读要用的 root 工具：**不连库**（本类只验重试，不验取数）。 */
    private static final class RootStubTool implements AgentTool {
        @Override public String name() { return OrderFactsTool.NAME; }
        @Override public String description() { return "重试用桩工具"; }
        @Override public java.util.Map<String, Object> parameterSchema() {
            return java.util.Map.of("type", "object",
                    "properties", java.util.Map.of("orderNo", java.util.Map.of("type", "string")),
                    "required", List.of("orderNo"));
        }
        @Override public ToolOutcome execute(ToolContext ctx, com.fasterxml.jackson.databind.JsonNode arguments) {
            java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
            facts.put("order.exists", "true");
            facts.put("order.status", "IN_PROGRESS");
            facts.put("order.assignee", "a*");
            return ToolOutcome.ok(facts);
        }
    }

    private static String toolTurn() {
        return "{\"orderNo\":\"" + ORDER_NO + "\"}";
    }

    @Test
    @DisplayName("429 两次后 200 → COMPLETED，且**物理调用 3 次**（桩计数为证）")
    void recoversAfterTwoRetries() {
        InvestigationAgent agent = agent(AgentLimits.s1Defaults(), PermissionRecheck.NONE,
                StubModelServer.httpError(429, "rate limited"),
                StubModelServer.httpError(429, "rate limited"),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));

        AgentRunResult result = agent.investigate(
                ToolContext.ofDepartment("inv-retry-1", "1", "7"), ORDER_NO, "随便问问");

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
        assertEquals(3, stub.requestCount(), "物理调用必须计入：429 + 429 + 200 = 3");
    }

    @Test
    @DisplayName("429 持续 → 用尽上限后 FAILED(MODEL_HTTP_ERROR)，物理调用次数 = 上限，report 为 null")
    void persistent429FailsAfterMaxAttempts() {
        InvestigationAgent agent = agent(AgentLimits.s1Defaults(), PermissionRecheck.NONE,
                StubModelServer.httpError(429, "rate limited"),
                StubModelServer.httpError(429, "rate limited"),
                StubModelServer.httpError(429, "rate limited"),
                StubModelServer.httpError(429, "rate limited"));

        AgentRunResult result = agent.investigate(
                ToolContext.ofDepartment("inv-retry-2", "1", "7"), ORDER_NO, "随便问问");

        assertEquals(AgentStatus.FAILED, result.status());
        assertEquals("MODEL_HTTP_ERROR", result.failure().code(), "复用既有原因码，不新开码");
        assertTrue(result.failure().message().contains("物理调用 3 次"),
                "重试不能藏起来（消息里要带物理调用次数）：" + result.failure().message());
        assertNull(result.report(), "失败不得产出报告");
        assertEquals(3, stub.requestCount(), "物理调用次数 = 上限（3），不再多试");
    }

    @Test
    @DisplayName("400（非 429 的 4xx）→ **不重试**：物理调用 1 次即 FAILED(MODEL_HTTP_ERROR)")
    void badRequestIsNotRetried() {
        InvestigationAgent agent = agent(AgentLimits.s1Defaults(), PermissionRecheck.NONE,
                StubModelServer.httpError(400, "bad request"));

        AgentRunResult result = agent.investigate(
                ToolContext.ofDepartment("inv-retry-3", "1", "7"), ORDER_NO, "随便问问");

        assertEquals(AgentStatus.FAILED, result.status());
        assertEquals("MODEL_HTTP_ERROR", result.failure().code());
        assertEquals(1, stub.requestCount(), "4xx 重试多少次都一样——属'无谓重试'");
    }

    @Test
    @DisplayName("等待计入预算：长 Retry-After + 很小的运行预算 → 不得睡过预算，TIMED_OUT(RUN_BUDGET_EXCEEDED)")
    void waitMustNotSleepPastRunBudget() {
        InvestigationAgent agent = agent(AgentLimits.s1Defaults().withRunBudget(Duration.ofMillis(300)),
                PermissionRecheck.NONE,
                StubModelServer.httpErrorWithRetryAfter(429, "rate limited", 5),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));

        long started = System.nanoTime();
        AgentRunResult result = agent.investigate(
                ToolContext.ofDepartment("inv-retry-4", "1", "7"), ORDER_NO, "随便问问");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(AgentStatus.TIMED_OUT, result.status());
        assertEquals("RUN_BUDGET_EXCEEDED", result.failure().code(),
                "等待会超出本轮预算 → 不睡，按预算耗尽收口：" + result.failure().message());
        assertEquals(1, stub.requestCount(), "第二次物理调用都没发起");
        assertTrue(elapsedMillis < 2500, "不许真的睡满 Retry-After（5s）：实际 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("重试不绕过工具边界的权限重校验：重试期间的撤权仍在下一个工具调用前生效")
    void revocationDuringRetryStillApplies() {
        WorkOrderService service = mock(WorkOrderService.class);
        when(service.resolveDepartmentScope(anyLong()))
                .thenReturn(WorkOrderService.DepartmentScope.department(7))    // 受理期快照：DEPT 7
                .thenReturn(WorkOrderService.DepartmentScope.department(8));   // 重试之后：已被调到 DEPT 8
        InvestigationAgent agent = agent(AgentLimits.s1Defaults(), new PermissionRecheck(service),
                StubModelServer.httpError(429, "rate limited"),
                StubModelServer.json(StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME, toolTurn())));

        AgentRunResult result = agent.investigate(
                ToolContext.ofDepartment("inv-retry-5", "1", "7"), ORDER_NO, "随便问问");

        assertEquals(AgentStatus.CANCELLED, result.status(), () -> "failure=" + result.failure());
        assertEquals("PERMISSION_REVOKED", result.failure().code());
        assertNull(result.report());
        assertEquals(2, stub.requestCount(), "429 之后重试了一次（物理调用 2 次）");
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
