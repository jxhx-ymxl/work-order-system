package com.workorder.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §4.3 的两道闸门：用户级频率限制 + 全局模型调用预算。
 *
 * <p>判据：按用户维度计数、窗口滑动（用**可注入时钟**推进，不 sleep）、全局预算用尽对所有人关门、
 * 被拒时**不消耗模型调用**、拒绝路径**不泄漏并发名额**、**三类拒绝码互不相同**。
 * 全部本地 HTTP 桩，零真实费用。
 */
@DisplayName("限流：用户级频率 + 全局模型调用预算（§4.3）")
class InvestigationThrottleTest {

    private static final String ORDER_NO = "WO-20261006-00026";
    private static final long USER_A = 1L;
    private static final long USER_B = 2L;

    private StubModelServer stub;

    @AfterEach
    void tearDown() {
        if (stub != null) {
            stub.close();
        }
    }

    /** 可推进的时钟：窗口行为必须能稳定复现，靠 sleep 既慢又脆。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T00:00:00Z");

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static WorkOrderService departmentService() {
        WorkOrderService service = mock(WorkOrderService.class);
        when(service.resolveDepartmentScope(anyLong()))
                .thenReturn(WorkOrderService.DepartmentScope.department(7));
        return service;
    }

    private AgentInvestigationService service(InvestigationThrottle throttle, int repeats) {
        StubModelServer.Reply[] script = new StubModelServer.Reply[repeats];
        for (int i = 0; i < repeats; i++) {
            script[i] = StubModelServer.json(StubModelServer.finishTurn("UNSUPPORTED", List.of(), List.of()));
        }
        stub = new StubModelServer(script);
        AgentLimits limits = AgentLimits.s1Defaults();
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new RootStubTool()));
        HttpAgentModel model = new HttpAgentModel(stub.url(), "stub-key", "stub-model", registry.definitions(),
                limits.modelConnectTimeout(), limits.modelReadTimeout(), limits.maxModelResponseBytes());
        InvestigationAgent agent = new InvestigationAgent(model, registry, limits, OrderFactsTool.NAME,
                FinalReview.NONE, PermissionRecheck.NONE);
        return new AgentInvestigationService(agent, null, new AgentReportRenderer(),
                AgentInvestigationService.MODE_AGENT, departmentService(), 1, throttle);
    }

    @Test
    @DisplayName("按用户维度：A 连发 N 次后第 N+1 次被频率拒绝，B 不受影响")
    void rateLimitIsPerUser() {
        InvestigationThrottle throttle = new InvestigationThrottle(Duration.ofMinutes(1), 2, 100,
                new MutableClock());
        AgentInvestigationService service = service(throttle, 10);

        assertEquals("COMPLETED", service.investigate(USER_A, ORDER_NO, "随便问问").status());
        assertEquals("COMPLETED", service.investigate(USER_A, ORDER_NO, "随便问问").status());
        AgentInvestigationService.Outcome third = service.investigate(USER_A, ORDER_NO, "随便问问");

        assertEquals(AgentInvestigationService.CODE_RATE_LIMITED, third.status());
        assertEquals(AgentInvestigationService.CODE_RATE_LIMITED, third.failureCode());
        assertEquals("COMPLETED", service.investigate(USER_B, ORDER_NO, "随便问问").status(),
                "频率是**按用户**算的，B 不该被 A 用掉");
    }

    @Test
    @DisplayName("窗口滑过 → A 又能进（用可注入时钟推进，不 sleep）")
    void windowSlidesWithTheInjectedClock() {
        MutableClock clock = new MutableClock();
        InvestigationThrottle throttle = new InvestigationThrottle(Duration.ofMinutes(1), 1, 100, clock);
        AgentInvestigationService service = service(throttle, 10);

        assertEquals("COMPLETED", service.investigate(USER_A, ORDER_NO, "随便问问").status());
        assertEquals(AgentInvestigationService.CODE_RATE_LIMITED,
                service.investigate(USER_A, ORDER_NO, "随便问问").status());

        clock.advance(Duration.ofSeconds(61));

        assertEquals("COMPLETED", service.investigate(USER_A, ORDER_NO, "随便问问").status(),
                "窗口滑过之后必须能再进");
    }

    @Test
    @DisplayName("全局预算用尽 → 即使频率没到也被拒（BUDGET_EXHAUSTED）")
    void globalBudgetRejectsEveryone() {
        // 预算只够 1 次模型调用；第一次调查用掉 1 次逻辑轮次 → 之后所有人被拒
        InvestigationThrottle throttle = new InvestigationThrottle(Duration.ofMinutes(1), 10, 1,
                new MutableClock());
        AgentInvestigationService service = service(throttle, 10);

        assertEquals("COMPLETED", service.investigate(USER_A, ORDER_NO, "随便问问").status());
        AgentInvestigationService.Outcome rejected = service.investigate(USER_B, ORDER_NO, "随便问问");

        assertEquals(AgentInvestigationService.CODE_BUDGET_EXHAUSTED, rejected.status());
        assertEquals(AgentInvestigationService.CODE_BUDGET_EXHAUSTED, rejected.failureCode());
    }

    @Test
    @DisplayName("被拒时**不消耗模型调用**（桩计数为 0）")
    void rejectedRequestDoesNotSpendModelCalls() {
        InvestigationThrottle throttle = new InvestigationThrottle(Duration.ofMinutes(1), 1, 100,
                new MutableClock());
        AgentInvestigationService service = service(throttle, 10);

        assertEquals("COMPLETED", service.investigate(USER_A, ORDER_NO, "随便问问").status());
        int before = stub.requestCount();

        assertEquals(AgentInvestigationService.CODE_RATE_LIMITED,
                service.investigate(USER_A, ORDER_NO, "随便问问").status());

        assertEquals(before, stub.requestCount(), "被拒绝的请求不得发起任何模型调用");
    }

    @Test
    @DisplayName("拒绝路径不泄漏并发名额：被拒之后下一个正常请求仍能进")
    void rejectionDoesNotLeakThePermit() {
        InvestigationThrottle throttle = new InvestigationThrottle(Duration.ofMinutes(1), 1, 100,
                new MutableClock());
        AgentInvestigationService service = service(throttle, 10);

        assertEquals("COMPLETED", service.investigate(USER_A, ORDER_NO, "随便问问").status());
        assertEquals(AgentInvestigationService.CODE_RATE_LIMITED,
                service.investigate(USER_A, ORDER_NO, "随便问问").status());

        assertEquals(1, service.availablePermits(), "拒绝路径不能动名额");
        assertEquals("COMPLETED", service.investigate(USER_B, ORDER_NO, "随便问问").status(),
                "另一个用户仍然能进（名额没被拒绝路径吃掉）");
    }

    @Test
    @DisplayName("三类拒绝的码互不相同：AGENT_BUSY / RATE_LIMITED / BUDGET_EXHAUSTED")
    void threeRejectionCodesAreDistinct() {
        InvestigationThrottle rateThrottle = new InvestigationThrottle(Duration.ofMinutes(1), 1, 100,
                new MutableClock());
        AgentInvestigationService rateService = service(rateThrottle, 10);
        rateService.investigate(USER_A, ORDER_NO, "随便问问");
        String rateCode = rateService.investigate(USER_A, ORDER_NO, "随便问问").failureCode();

        // 预算 0：**第一次**就会被拒（判断在发起任何模型调用之前）
        InvestigationThrottle budgetThrottle = new InvestigationThrottle(Duration.ofMinutes(1), 1, 0,
                new MutableClock());
        AgentInvestigationService budgetService = service(budgetThrottle, 10);
        String budgetCode = budgetService.investigate(USER_A, ORDER_NO, "随便问问").failureCode();

        assertEquals(AgentInvestigationService.CODE_RATE_LIMITED, rateCode);
        assertEquals(AgentInvestigationService.CODE_BUDGET_EXHAUSTED, budgetCode);
        assertEquals(AgentInvestigationService.CODE_AGENT_BUSY, "AGENT_BUSY");
        assertNotEquals(rateCode, budgetCode, "频率与全局预算是两回事");
        assertNotEquals(rateCode, AgentInvestigationService.CODE_AGENT_BUSY, "频率与并发位是两回事");
        assertNotEquals(budgetCode, AgentInvestigationService.CODE_AGENT_BUSY, "预算与并发位是两回事");
    }

    private static final class RootStubTool implements AgentTool {
        @Override public String name() { return OrderFactsTool.NAME; }
        @Override public String description() { return "限流测试用桩工具"; }
        @Override public Map<String, Object> parameterSchema() {
            return Map.of("type", "object",
                    "properties", Map.of("orderNo", Map.of("type", "string")),
                    "required", List.of("orderNo"));
        }
        @Override public ToolOutcome execute(ToolContext ctx, JsonNode arguments) {
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("order.exists", "true");
            return ToolOutcome.ok(facts);
        }
    }
}
