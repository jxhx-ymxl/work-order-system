package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真正的取消（§11-3 / §4.2）。
 *
 * <p>判据：置位后**不再发下一个请求**；终态 `CANCELLED(USER_CANCELLED)` 且 `report == null`；
 * **不泄漏名额**（下一个能进）；**取消延迟不超过当前轮读超时**（用桩的流式响应把这条量出来）。
 * 全部本地 HTTP 桩，零真实费用。
 */
@DisplayName("真正的取消（USER_CANCELLED）")
class CancellationTest {

    private static final String ORDER_NO = "WO-20261006-00025";
    private static final ToolContext CTX = ToolContext.ofDepartment("inv-cancel-1", "1", "7");

    private StubModelServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    private static WorkOrderService departmentService() {
        WorkOrderService service = mock(WorkOrderService.class);
        when(service.resolveDepartmentScope(anyLong()))
                .thenReturn(WorkOrderService.DepartmentScope.department(7));
        return service;
    }

    private InvestigationAgent agent(AgentLimits limits, StubModelServer.Reply... script) {
        stub = new StubModelServer(script);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new RootStubTool()));
        HttpAgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        return new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME, FinalReview.NONE,
                PermissionRecheck.NONE);
    }

    /**
     * **模型第一轮响应之后**置位取消：模型给出了工具调用、请求已发出（requestCount=1），
     * 但调查循环进入第二轮之前会发现取消 → 不再发下一个请求。
     *
     * <p>注意**不能**用"工具第 2 次执行时取消"来构造：入口预读会把同一参数写进本轮快照缓存，
     * 模型第一轮的相同调用命中缓存、工具**不会再执行**（这是"预读计成本且不重复执行"的既有语义）。
     */
    private static StubModelServer.Reply cancelAfterResponding(Cancellation cancellation,
                                                               StubModelServer.Reply reply) {
        return exchange -> {
            reply.respond(exchange);
            cancellation.cancel("用户点了取消");
        };
    }

    @Test
    @DisplayName("置位后不再发下一个请求：终态 CANCELLED(USER_CANCELLED)，report 为 null")
    void cancellationStopsFurtherRequests() {
        Cancellation cancellation = Cancellation.create();
        AgentLimits limits = AgentLimits.s1Defaults();
        stub = new StubModelServer(
                cancelAfterResponding(cancellation, StubModelServer.json(
                        StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME, "{\"orderNo\":\"" + ORDER_NO + "\"}"))),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new RootStubTool()));
        HttpAgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        InvestigationAgent agent = new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME,
                FinalReview.NONE, PermissionRecheck.NONE);

        AgentRunResult result = agent.investigate(CTX, ORDER_NO, "随便问问", cancellation);

        assertEquals(AgentStatus.CANCELLED, result.status());
        assertEquals("USER_CANCELLED", result.failure().code());
        assertNull(result.report(), "取消不得产出报告");
        assertEquals(1, stub.requestCount(), "置位之后**不再发下一个请求**");
    }

    @Test
    @DisplayName("取消期间不泄漏名额：CANCELLED 之后下一个能进")
    void cancellationDoesNotLeakThePermit() {
        Cancellation cancellation = Cancellation.create();
        AgentLimits limits = AgentLimits.s1Defaults();
        stub = new StubModelServer(
                cancelAfterResponding(cancellation, StubModelServer.json(
                        StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME, "{\"orderNo\":\"" + ORDER_NO + "\"}"))),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new RootStubTool()));
        HttpAgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        InvestigationAgent agent = new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME,
                FinalReview.NONE, PermissionRecheck.NONE);
        AgentInvestigationService service = new AgentInvestigationService(agent, null, new AgentReportRenderer(),
                AgentInvestigationService.MODE_AGENT, departmentService(), 1);

        AgentInvestigationService.Outcome cancelled = service.investigate(1L, ORDER_NO, "随便问问", cancellation);

        assertEquals("CANCELLED", cancelled.status());
        assertEquals(1, service.availablePermits(), "取消也必须归还名额（finally 覆盖所有终止路径）");
        assertEquals("COMPLETED", service.investigate(1L, ORDER_NO, "随便问问").status(),
                "名额归还后，下一个调查必须能进");
    }

    @Test
    @DisplayName("取消延迟不超过当前轮读超时（用流式响应量这条上界）")
    void cancellationLatencyIsBoundedByTheRoundReadTimeout() {
        Cancellation cancellation = Cancellation.create();
        AgentLimits limits = AgentLimits.s1Defaults().withModelReadTimeout(Duration.ofSeconds(3));
        stub = new StubModelServer(StubModelServer.endless(4096));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new RootStubTool()));
        HttpAgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        InvestigationAgent agent = new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME,
                FinalReview.NONE, PermissionRecheck.NONE);

        AtomicReference<AgentRunResult> holder = new AtomicReference<>();
        Thread worker = new Thread(() -> holder.set(agent.investigate(CTX, ORDER_NO, "随便问问", cancellation)));
        long started = System.nanoTime();
        worker.start();
        // 读取循环里每个 chunk 都检查标志：等它开始读之后再取消
        waitUntil(() -> stub.requestCount() >= 1, 3000);
        cancellation.cancel("用户点了取消");
        try {
            worker.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(AgentStatus.CANCELLED, holder.get().status(), () -> "failure=" + holder.get().failure());
        assertEquals("USER_CANCELLED", holder.get().failure().code());
        assertNull(holder.get().report());
        assertTrue(elapsedMillis < 3000,
                "取消延迟必须 ≤ 当前轮读超时 3000ms：实际 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("没取消 → 仍然 COMPLETED（防误报）")
    void withoutCancellationStillCompletes() {
        InvestigationAgent agent = agent(AgentLimits.s1Defaults(),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));

        AgentRunResult result = agent.investigate(CTX, ORDER_NO, "随便问问", Cancellation.create());

        assertEquals(AgentStatus.COMPLETED, result.status(), () -> "failure=" + result.failure());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class RootStubTool implements AgentTool {
        @Override public String name() { return OrderFactsTool.NAME; }
        @Override public String description() { return "取消测试用桩工具"; }
        @Override public Map<String, Object> parameterSchema() {
            return Map.of("type", "object",
                    "properties", Map.of("orderNo", Map.of("type", "string")),
                    "required", List.of("orderNo"));
        }
        @Override public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("order.exists", "true");
            facts.put("order.status", "IN_PROGRESS");
            facts.put("order.assignee", "a*");
            return ToolOutcome.ok(facts);
        }
    }
}
