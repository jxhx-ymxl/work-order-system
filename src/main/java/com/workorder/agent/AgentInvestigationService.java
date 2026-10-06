package com.workorder.agent;

import com.workorder.service.WorkOrderService;

import java.util.UUID;

/**
 * **受理层（非 HTTP）**：把「问题 + 已登录用户 id」变成「ToolContext + 报告 + 渲染文本」。
 *
 * <p>三条边界：
 * <ul>
 *   <li>{@link ToolContext} 在**这里一次性快照**（§11-2 / D79）——执行侧不重新取值；</li>
 *   <li>部门判定**与列表接口同源**：`callerDeptId` 来自 {@link WorkOrderService#callerDeptId}，
 *       与 `applyRoleFilters` 调用的是同一份实现，**不复制**；</li>
 *   <li>默认模式是 **fixed**（§1 第八题：没有可证明的净收益之前，业务默认不选 agent）。</li>
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

    public Outcome investigate(Long currentUserId, String question) {
        Long deptId = workOrderService.callerDeptId(currentUserId);
        if (deptId == null) {
            // 范围载体缺失在**受理期**就失败（§11-2：不允许执行期悄悄缩小或放宽）
            return new Outcome("FAILED", "FORBIDDEN", null, null);
        }
        ToolContext ctx = ToolContext.ofDepartment(
                "inv-" + UUID.randomUUID(), String.valueOf(currentUserId), String.valueOf(deptId));

        AgentRunResult result = MODE_AGENT.equals(mode)
                ? agent.investigate(ctx, question)
                : fixed.investigate(ctx, question);

        String rendered = result.report() == null
                ? null   // 非 COMPLETED 不产出文本（与 report 的不变量一致）
                : renderer.render(result.report(), result.evidence());
        return new Outcome(result.status().name(),
                result.failure() == null ? null : result.failure().code(),
                result.report(), rendered);
    }
}
