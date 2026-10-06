package com.workorder.agent;

import com.workorder.service.WorkOrderService;

import java.util.ArrayList;
import java.util.List;

/**
 * **工具调用前的权限重校验**（`docs/agent-design/AGENT-DESIGN.md` L116 第 3 条）。
 *
 * <p>设计稿原话："每次工具重新校验会话、账号、权限、主管当前部门。**部门在首读后固定**；
 * 主管调部门则**终止，不切换到新范围**。"
 *
 * <p><b>会话本身不重校验（有意的偏离）</b>：执行线程（S4 的消费 / 调度线程）**没有 Sa-Token 会话**——
 * §11-2 已定稿"执行侧不重新取值，那里没有会话，想取也取不到"。所以本轮**只重校验数据层的
 * 账号 / 角色 / 部门**，**不伪造一个会话检查**：会话校验在执行期**不适用**。
 *
 * <p><b>判定同源</b>：准入用 {@link WorkOrderService#resolveDepartmentScope}——与列表接口、与受理层
 * **同一个方法**（D85）。本类**不自己写** {@code isAdmin()} 之类的本地判断。
 *
 * <p><b>与 {@link FinalReview} / {@link EvidenceLedger} 同一模式</b>：agent 与基线**共用同一个实例**，
 * 否则 S6 的成对比较比的就是两套判据。
 *
 * <p><b>代价</b>（D91）：每次工具调用前多一次"账号/角色/部门"解析查询。
 * 本轮**不做缓存**——缓存会把"撤销后仍按旧范围跑"重新引进来，正是本条要防的事；
 * 若将来要优化，必须先定缓存失效口径（登记为待优化项）。
 */
public final class PermissionRecheck {

    /** 不重校验（测试/无库场景用；生产装配必须给真实实现）。 */
    public static final PermissionRecheck NONE = new PermissionRecheck(null);

    private final WorkOrderService workOrderService;

    public PermissionRecheck(WorkOrderService workOrderService) {
        this.workOrderService = workOrderService;
    }

    public boolean isEnabled() {
        return workOrderService != null;
    }

    /**
     * 返回"为什么已被撤销"；**空列表 = 仍然有效**。
     *
     * <p>任何一种情况都算撤销：① 已不是 `DEPT_ADMIN`（或已无部门）；② 部门与受理期快照不一致；
     * ③ 快照里的部门载体不可解析。**一律终止**，**不得按新部门继续查**。
     */
    public List<String> revokedReasons(ToolContext ctx) {
        if (!isEnabled()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        Long userId;
        try {
            userId = Long.valueOf(ctx.callerUserId());
        } catch (NumberFormatException e) {
            reasons.add("调用者身份载体不可解析（callerUserId=" + ctx.callerUserId() + "）");
            return reasons;
        }
        long snapshotDeptId;
        try {
            snapshotDeptId = Long.valueOf(ctx.callerDeptId());
        } catch (NumberFormatException e) {
            reasons.add("部门载体不可解析（callerDeptId=" + ctx.callerDeptId() + "）");
            return reasons;
        }

        WorkOrderService.DepartmentScope now = workOrderService.resolveDepartmentScope(userId);
        if (!now.isDepartment()) {
            reasons.add("调用者已不是部门主管（或已无部门）");
            return reasons;
        }
        if (now.deptId() != snapshotDeptId) {
            // 关键：**终止**，不是"按新部门继续查"
            reasons.add("部门已变更：受理期快照 " + snapshotDeptId + " → 现在 " + now.deptId());
        }
        return reasons;
    }
}
