package com.workorder.agent;

import com.workorder.service.WorkOrderService;

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

    private final InvestigationAgent agent;
    private final FixedFlowInvestigator fixed;
    private final AgentReportRenderer renderer;
    private final String mode;
    private final WorkOrderService workOrderService;

    public AgentInvestigationService(InvestigationAgent agent, FixedFlowInvestigator fixed,
                                     AgentReportRenderer renderer, String mode,
                                     WorkOrderService workOrderService) {
        this.agent = agent;
        this.fixed = fixed;
        this.renderer = renderer;
        this.mode = mode;
        this.workOrderService = workOrderService;
    }

    public String mode() {
        return mode;
    }

    public Outcome investigate(Long currentUserId, String orderNo, String question) {
        WorkOrderService.DepartmentScope scope = workOrderService.resolveDepartmentScope(currentUserId);
        if (!scope.isDepartment()) {
            // 拿不到部门范围（非部门主管 / 无部门）在**受理期**就失败：没有范围 = 没有过滤条件（§11-2 / D79 / D85）
            return new Outcome("FAILED", "FORBIDDEN", null, null);
        }
        ToolContext ctx = ToolContext.ofDepartment(
                "inv-" + UUID.randomUUID(), String.valueOf(currentUserId), String.valueOf(scope.deptId()));

        AgentRunResult result = MODE_AGENT.equals(mode)
                ? agent.investigate(ctx, orderNo, question)
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
}
