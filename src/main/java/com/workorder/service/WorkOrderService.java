package com.workorder.service;

import com.workorder.common.PageResult;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.StatsVO;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.WorkOrder;

import java.util.List;

public interface WorkOrderService {

    WorkOrder submitOrder(SubmitOrderReq req, Long submitterId);

    PageResult<WorkOrderVO> listOrders(PageQuery query, Long currentUserId);

    WorkOrderDetailVO getOrderDetail(Long orderId, Long currentUserId);

    /**
     * 工单操作日志——**带数据级越权校验**，可见性判定与 {@link #getOrderDetail} 同一处（同源）。
     *
     * <p>为什么要单独一个带身份的入口：`WorkOrderLogService.queryLogs(orderId)` 只按工单过滤，
     * 谁都能调；日志里含操作人姓名与备注，不能只靠"登录即可读"。
     *
     * @throws com.workorder.common.BizException 工单不存在（NOT_FOUND）或无权查看（FORBIDDEN）
     */
    List<com.workorder.common.vo.WorkOrderLogVO> getOrderLogs(Long orderId, Long currentUserId);

    /**
     * 调用者所在部门 id（无部门返回 {@code null}）。
     *
     * <p><b>它只是"部门解析"这一步</b>，**不是**"能否拿到部门范围"的准入判定——后者见
     * {@link #resolveDepartmentScope(Long)}。单独用它构造范围会漏掉角色准入（历史缺陷：任何有部门的
     * 普通用户都能拿到全部门范围），所以新代码一律走 {@code resolveDepartmentScope}。
     */
    Long callerDeptId(Long currentUserId);

    /**
     * 某部门的成员用户 id 列表——与 `applyRoleFilters` 的部门分支**同一份实现**。
     *
     * <p>用途：列表的"部门主管可见范围"与 agent 工具的"同部门提交人范围"必须是同一个集合。
     */
    List<Long> departmentMemberIds(Long deptId);

    /**
     * **"谁能拿到部门范围 + 拿到哪个部门"的唯一真源**（`docs/DECISIONS.md` D79 / D85）。
     *
     * <p>列表接口的 {@code applyRoleFilters} 与 agent 受理层（做 {@link com.workorder.agent.ToolContext} 快照）
     * **共同调用它**，不得各写一套准入 {@code if}——两处判定迟早漂移，而漂移的方向总是放宽。
     *
     * <p>准入规则（`docs/AGENT-PLAN.md` §1 第一题：助手面向**部门主管**）：{@code DEPT_ADMIN} 且部门非空 →
     * {@code DEPARTMENT(deptId)}；其它角色（含 {@code SYS_ADMIN}）→ {@code NONE}。
     * <b>{@code SYS_ADMIN} 是否可用登记为待裁决</b>，本轮不放行（放行须走 D 条目）。
     */
    DepartmentScope resolveDepartmentScope(Long userId);

    /** 部门范围：{@code NONE}（{@code deptId == null}）或 {@code DEPARTMENT(deptId)}。 */
    record DepartmentScope(Long deptId) {
        public static DepartmentScope none() {
            return new DepartmentScope(null);
        }

        public static DepartmentScope department(long deptId) {
            return new DepartmentScope(deptId);
        }

        public boolean isDepartment() {
            return deptId != null;
        }
    }

    List<StatsVO> getStats(String scope, Long currentUserId);

    void acceptOrder(Long orderId, Long userId);

    void startOrder(Long orderId, Long operatorId);

    void completeOrder(Long orderId, Long operatorId);

    void approveOrder(Long orderId, Long operatorId);

    void rejectOrder(Long orderId, Long operatorId, String remark);

    /**
     * 超时释放工单（显式三态，P1 步骤 4）。
     *
     * @return {@link com.workorder.common.enums.ReleaseResult}：RELEASED 真释放 / SKIPPED 状态守卫未命中 /
     *         ERROR 内部出错。<b>不再用"抛异常"表达"工单不存在"</b>——调用方（兜底扫描、MQ 消费者）
     *         需要能区分三态来决定 ACK/NACK 与日志级别。
     */
    com.workorder.common.enums.ReleaseResult releaseOrder(Long orderId);

    void assignOrder(Long orderId, Long assigneeId, Long operatorId);

    /** Issue#P1: 管理员接管升级工单（ESCALATED_ADMIN -> IN_PROGRESS，接管人=assignee） */
    void manageEscalatedOrder(Long orderId, Long operatorId);

    /** Issue#P1: 系统管理员强制关闭升级工单（ESCALATED_ADMIN -> CLOSED） */
    void closeEscalatedOrder(Long orderId, Long operatorId);

    /** Issue #32: 生成驳回一次性 Token，有效期 30 秒 */
    String generateRejectToken(Long orderId);

    /** Issue #32: Lua 脚本原子校验并消费 Token，返回 true 表示通过 */
    boolean validateAndConsumeRejectToken(Long orderId, String token);
}
