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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 有界并发位 + 忙碌响应 + 名额归还（槽 23 的前半；§4.3）。
 *
 * <p>判据：取不到名额**立即**拒绝（不排队，用耗时证明）；每条终止路径都归还（用"下一个能进/剩余名额"证明）；
 * 归还**幂等**（多次运行后名额不膨胀）；忙碌**不产生报告、不消耗模型调用**。
 * 全部本地 HTTP 桩，零真实费用。
 */
@DisplayName("并发名额：忙碌拒绝与归还（槽 23）")
class InvestigationConcurrencyTest {

    private static final String ORDER_NO = "WO-20261006-00023";
    private static final ToolContext CTX = ToolContext.ofDepartment("inv-conc-1", "1", "7");

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

    /** 带延时的"正常收尾"回复：用来把第一次调查卡在模型调用里。 */
    private static StubModelServer.Reply slowFinish(long delayMillis) {
        return StubModelServer.delayed(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of()),
                delayMillis);
    }

    private AgentInvestigationService service(WorkOrderService workOrderService, int maxConcurrent,
                                              AgentLimits limits, PermissionRecheck recheck,
                                              StubModelServer.Reply... script) {
        stub = new StubModelServer(script);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new RootStubTool()));
        HttpAgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        InvestigationAgent agent = new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME,
                FinalReview.NONE, recheck);
        FixedFlowInvestigator fixed = new FixedFlowInvestigator(registry, limits);
        return new AgentInvestigationService(agent, fixed, new AgentReportRenderer(),
                AgentInvestigationService.MODE_AGENT, workOrderService, maxConcurrent);
    }

    @Test
    @DisplayName("并发两个：第二个拿到忙碌码、**没有等待**、也没消耗模型调用")
    void secondConcurrentCallIsRejectedImmediately() throws Exception {
        AgentInvestigationService service = service(departmentService(), 1, AgentLimits.s1Defaults(),
                PermissionRecheck.NONE, slowFinish(1500), slowFinish(1500));

        AtomicReference<AgentInvestigationService.Outcome> first = new AtomicReference<>();
        Thread worker = new Thread(() -> first.set(service.investigate(1L, ORDER_NO, "随便问问")));
        worker.start();
        waitUntil(() -> stub.requestCount() == 1, 3000);

        long started = System.nanoTime();
        AgentInvestigationService.Outcome second = service.investigate(1L, ORDER_NO, "随便问问");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(AgentInvestigationService.STATUS_BUSY, second.status());
        assertEquals(AgentInvestigationService.CODE_AGENT_BUSY, second.failureCode());
        assertNull(second.report(), "忙碌不得产出报告");
        assertNull(second.renderedText(), "忙碌不得产出文本");
        assertTrue(elapsedMillis < 700, "忙碌必须**立即**返回、不排队：实际 " + elapsedMillis + "ms");
        assertEquals(1, stub.requestCount(), "被拒绝的那次**没有**消耗模型调用");

        worker.join(5000);
        assertEquals("COMPLETED", first.get().status());
    }

    @Test
    @DisplayName("第一个结束后 → 第三个能进（证明名额归还了）")
    void permitIsReturnedAfterCompletion() throws Exception {
        AgentInvestigationService service = service(departmentService(), 1, AgentLimits.s1Defaults(),
                PermissionRecheck.NONE, slowFinish(300), slowFinish(0));

        AtomicReference<AgentInvestigationService.Outcome> first = new AtomicReference<>();
        Thread worker = new Thread(() -> first.set(service.investigate(1L, ORDER_NO, "随便问问")));
        worker.start();
        waitUntil(() -> stub.requestCount() == 1, 3000);
        assertEquals(AgentInvestigationService.STATUS_BUSY, service.investigate(1L, ORDER_NO, "随便问问").status());

        worker.join(5000);

        AgentInvestigationService.Outcome third = service.investigate(1L, ORDER_NO, "随便问问");
        assertEquals("COMPLETED", third.status(), "名额归还之后必须能再进");
        assertEquals(1, service.availablePermits(), "容量为 1：归还后仍只有 1 个名额");
    }

    @Test
    @DisplayName("终止路径全覆盖：FAILED / TIMED_OUT / CANCELLED 之后名额都归还")
    void everyTerminalPathReturnsThePermit() {
        // ① FAILED（400 不重试）
        AgentInvestigationService failedRun = service(departmentService(), 1, AgentLimits.s1Defaults(),
                PermissionRecheck.NONE, StubModelServer.httpError(400, "bad request"));
        assertEquals("FAILED", failedRun.investigate(1L, ORDER_NO, "随便问问").status());
        assertEquals(1, failedRun.availablePermits(), "FAILED 之后必须归还");

        // ② TIMED_OUT（小预算 + 慢响应）
        AgentInvestigationService timedOutRun = service(departmentService(), 1,
                AgentLimits.s1Defaults().withRunBudget(Duration.ofMillis(200)),
                PermissionRecheck.NONE, slowFinish(3000));
        assertEquals("TIMED_OUT", timedOutRun.investigate(1L, ORDER_NO, "随便问问").status());
        assertEquals(1, timedOutRun.availablePermits(), "TIMED_OUT 之后必须归还");

        // ③ CANCELLED（重试/工具边界上撤权）
        WorkOrderService revoking = mock(WorkOrderService.class);
        when(revoking.resolveDepartmentScope(anyLong()))
                .thenReturn(WorkOrderService.DepartmentScope.department(7))
                .thenReturn(WorkOrderService.DepartmentScope.none());
        AgentInvestigationService cancelledRun = service(revoking, 1, AgentLimits.s1Defaults(),
                new PermissionRecheck(revoking),
                StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of())));
        assertEquals("CANCELLED", cancelledRun.investigate(1L, ORDER_NO, "随便问问").status());
        assertEquals(1, cancelledRun.availablePermits(), "CANCELLED 之后必须归还");
    }

    @Test
    @DisplayName("重复归还不放大：连续跑 5 次之后，剩余名额仍是容量（不是 5）")
    void repeatedRunsDoNotInflateThePermit() {
        AgentInvestigationService service = service(departmentService(), 1, AgentLimits.s1Defaults(),
                PermissionRecheck.NONE, slowFinish(0), slowFinish(0), slowFinish(0), slowFinish(0), slowFinish(0));

        for (int i = 0; i < 5; i++) {
            assertEquals("COMPLETED", service.investigate(1L, ORDER_NO, "随便问问").status());
        }

        assertEquals(1, service.availablePermits(),
                "归还必须幂等：若每次多 release 一次，这里会变成 5");
    }

    @Test
    @DisplayName("容量可配：容量 2 时，两个并发都能进（第三个才忙碌）")
    void capacityIsConfigurable() throws Exception {
        AgentInvestigationService service = service(departmentService(), 2, AgentLimits.s1Defaults(),
                PermissionRecheck.NONE, slowFinish(600), slowFinish(600), slowFinish(0));

        AtomicReference<AgentInvestigationService.Outcome> first = new AtomicReference<>();
        AtomicReference<AgentInvestigationService.Outcome> second = new AtomicReference<>();
        Thread t1 = new Thread(() -> first.set(service.investigate(1L, ORDER_NO, "随便问问")));
        t1.start();
        waitUntil(() -> stub.requestCount() == 1, 3000);
        Thread t2 = new Thread(() -> second.set(service.investigate(1L, ORDER_NO, "随便问问")));
        t2.start();
        waitUntil(() -> stub.requestCount() == 2, 3000);

        assertEquals(AgentInvestigationService.STATUS_BUSY,
                service.investigate(1L, ORDER_NO, "随便问问").status(), "容量 2 已满 → 第三个忙碌");

        t1.join(5000);
        t2.join(5000);
        assertNotNull(first.get());
        assertNotNull(second.get());
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

    /** 入口预读要用的 root 工具：不连库（本类只验并发与归还）。 */
    private static final class RootStubTool implements AgentTool {
        @Override public String name() { return OrderFactsTool.NAME; }
        @Override public String description() { return "并发测试用桩工具"; }
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
