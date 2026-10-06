package com.workorder.agent;

import com.workorder.service.WorkOrderService;

import java.util.concurrent.Semaphore;
import java.util.UUID;

/**
 * **受理层（非 HTTP）**：把「起点单 + 问题 + 已登录用户 id」变成「ToolContext + 报告 + 渲染文本」。
 *
 * <p>三条边界：
 * <ul>
 *   <li>{@link ToolContext} 在**这里一次性快照**（§11-2 / D79）——执行侧不重新取值；</li>
 *   <li>部门范围的**准入 + 取值**与列表接口同源：走 {@link WorkOrderService#resolveDepartmentScope}
 *       （与 `applyRoleFilters` 同一份判定，**不复制**）——不是"有部门就有范围"，
 *       非 {@code DEPT_ADMIN} 拿不到部门范围；</li>
 *   <li>默认模式是 **fixed**（§1 第八题：没有可证明的净收益之前，业务默认不选 agent）。</li>
 *   <li><b>起点单是结构化入参</b>（设计稿 L80）：{@code orderNo} 由调用方给出，
 *       **不从问题文本里解析**——问题文本只用于分类与向模型提问。</li>
 * </ul>
 *
 * <p><b>本片不做</b>：HTTP controller、异步任务、超时链与取消（需要服务器完整栈）。
 */
public class AgentInvestigationService {

    /** 受理结果：状态 / 失败码 / 报告（失败为 null）/ 渲染文本（失败为 null）。 */
    public record Outcome(String status, String failureCode, AgentReport report, String renderedText) {
    }

    public static final String MODE_FIXED = "fixed";
    public static final String MODE_AGENT = "agent";
    /** 忙碌拒绝的状态与原因码（**没有进入 RUNNING**——它是容量拒绝，不是调查的终态）。 */
    public static final String STATUS_BUSY = "BUSY";
    public static final String CODE_AGENT_BUSY = "AGENT_BUSY";

    private final InvestigationAgent agent;
    private final FixedFlowInvestigator fixed;
    private final AgentReportRenderer renderer;
    private final String mode;
    private final WorkOrderService workOrderService;
    /**
     * **有界并发位**（§4.3 成本控制）：容量可配、默认 1（§4.3 的标题就是"并发 1 之外"）。
     * 取不到 → **立即返回忙碌码**，**不排队、不阻塞**（§4.3："忙碌时的明确响应，不是静默排队"）。
     */
    private final Semaphore permits;

    public AgentInvestigationService(InvestigationAgent agent, FixedFlowInvestigator fixed,
                                     AgentReportRenderer renderer, String mode,
                                     WorkOrderService workOrderService) {
        this(agent, fixed, renderer, mode, workOrderService, 1);
    }

    public AgentInvestigationService(InvestigationAgent agent, FixedFlowInvestigator fixed,
                                     AgentReportRenderer renderer, String mode,
                                     WorkOrderService workOrderService, int maxConcurrent) {
        this.agent = agent;
        this.fixed = fixed;
        this.renderer = renderer;
        this.mode = mode;
        this.workOrderService = workOrderService;
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
    }

    public String mode() {
        return mode;
    }

    /** 剩余名额（测试与排障用；**不是**业务接口）。 */
    public int availablePermits() {
        return permits.availablePermits();
    }

    public Outcome investigate(Long currentUserId, String orderNo, String question) {
        return investigate(currentUserId, orderNo, question, Cancellation.NONE);
    }

    /**
     * 带**取消信号**的受理（§11-3）：取消一路传到模型读取循环（那里会主动断开连接）。
     *
     * <p>名额归还与取消无关地由 `finally` 保证——"取消期间不泄漏名额"靠的就是这一点（D93）。
     */
    public Outcome investigate(Long currentUserId, String orderNo, String question,
                              Cancellation cancellation) {
        // ① 有界并发位：取不到**立即**拒绝（不排队）——排队会让"忙碌"变成"悄悄变慢"
        if (!permits.tryAcquire()) {
            return new Outcome(STATUS_BUSY, CODE_AGENT_BUSY, null, null);
        }
        Permit permit = new Permit(permits);
        try {
            return run(currentUserId, orderNo, question, cancellation);
        } finally {
            // ② 每一条终止路径（COMPLETED / FAILED / TIMED_OUT / CANCELLED / INCOMPLETE / 抛异常）
            //    都在这里归还；归还发生在**执行体返回之后**（底层资源已经释放），且**只归还一次**。
            permit.releaseOnce();
        }
    }

    private Outcome run(Long currentUserId, String orderNo, String question, Cancellation cancellation) {
        WorkOrderService.DepartmentScope scope = workOrderService.resolveDepartmentScope(currentUserId);
        if (!scope.isDepartment()) {
            // 拿不到部门范围（非部门主管 / 无部门）在**受理期**就失败：没有范围 = 没有过滤条件（§11-2 / D79 / D85）
            return new Outcome("FAILED", "FORBIDDEN", null, null);
        }
        ToolContext ctx = ToolContext.ofDepartment(
                "inv-" + UUID.randomUUID(), String.valueOf(currentUserId), String.valueOf(scope.deptId()));

        AgentRunResult result = MODE_AGENT.equals(mode)
                ? agent.investigate(ctx, orderNo, question, cancellation)
                : fixed.investigate(ctx, orderNo, question);

        String rendered;
        if (result.report() != null) {
            rendered = renderer.render(result.report(), result.evidence());
        } else if (result.status() == AgentStatus.INCOMPLETE) {
            // 未完成**有**对外文本：顶部标"调查未完成 + 原因码"，再列已核实事实与缺口（D86）；
            // 但**不产出报告**（`report == null` 的不变量不变）。
            rendered = renderer.renderIncomplete(result.failure(), result.evidence());
        } else if (result.status() == AgentStatus.CANCELLED) {
            // 已取消（含运行中权限被撤销）同样有对外文本：顶部标"已取消 + 原因码"（§3.3 / L116）；
            // 与未完成同一形态，但措辞不能混——"权限被撤销"和"证据过期"对用户是两件事。
            rendered = renderer.renderCancelled(result.failure(), result.evidence());
        } else {
            rendered = null;   // FAILED / TIMED_OUT / CANCELLED：失败不产出文本
        }
        return new Outcome(result.status().name(),
                result.failure() == null ? null : result.failure().code(),
                result.report(), rendered);
    }

    /**
     * 名额句柄：**只归还一次**。
     *
     * <p>为什么必须显式守卫：`Semaphore.release()` **多调一次就多一个名额**——重复归还会让"有界"悄悄失效
     * （D93）。所以"已归还"这个状态由句柄自己记，调用方不需要（也不应该）去判断。
     */
    private static final class Permit {
        private final Semaphore semaphore;
        private boolean released;

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        private synchronized void releaseOnce() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }
}
