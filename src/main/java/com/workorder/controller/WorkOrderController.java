package com.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.PageResult;
import com.workorder.common.Result;
import com.workorder.common.dto.AssignReq;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.RejectReq;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "工单管理")
@SaCheckLogin
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final WorkOrderLogService workOrderLogService;

    @PostMapping
    @Operation(summary = "提交工单")
    public Result<WorkOrderVO> submit(@Valid @RequestBody SubmitOrderReq req) {
        Long submitterId = StpUtil.getLoginIdAsLong();
        WorkOrderVO vo = toVO(workOrderService.submitOrder(req, submitterId));
        return Result.ok(vo);
    }

    @GetMapping
    @Operation(summary = "工单列表")
    public Result<PageResult<WorkOrderVO>> list(PageQuery query) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        return Result.ok(workOrderService.listOrders(query, currentUserId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "工单详情（按角色/归属过滤可见性）")
    public Result<WorkOrderDetailVO> detail(@Parameter(description = "工单ID") @PathVariable Long id) {
        Long currentUserId = StpUtil.getLoginIdAsLong();
        return Result.ok(workOrderService.getOrderDetail(id, currentUserId));
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "工单操作日志")
    public Result<List<WorkOrderLogVO>> logs(@Parameter(description = "工单ID") @PathVariable Long id) {
        return Result.ok(workOrderLogService.queryLogs(id));
    }

    // ───────────────────── 状态流转端点 ─────────────────────

    @GetMapping("/{id}/action-token")
    @SaCheckPermission("order:reject")
    @Operation(summary = "获取驳回操作Token")
    public Result<String> getActionToken(@Parameter(description = "工单ID") @PathVariable Long id) {
        return Result.ok(workOrderService.generateRejectToken(id));
    }

    @PostMapping("/{id}/accept")
    @SaCheckPermission("order:accept")
    @Operation(summary = "抢单")
    public Result<Void> accept(@Parameter(description = "工单ID") @PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        workOrderService.acceptOrder(id, userId);
        return Result.ok();
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "开始处理")
    public Result<Void> start(@Parameter(description = "工单ID") @PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        workOrderService.startOrder(id, userId);
        return Result.ok();
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "提交验收")
    public Result<Void> complete(@Parameter(description = "工单ID") @PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        workOrderService.completeOrder(id, userId);
        return Result.ok();
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "验收通过")
    public Result<Void> approve(@Parameter(description = "工单ID") @PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        workOrderService.approveOrder(id, userId);
        return Result.ok();
    }

    @PostMapping("/{id}/reject")
    @SaCheckPermission("order:reject")
    @Operation(summary = "验收驳回（需携带Token）")
    public Result<Void> reject(@Parameter(description = "工单ID") @PathVariable Long id,
                               @Valid @RequestBody RejectReq req) {
        Long userId = StpUtil.getLoginIdAsLong();
        if (!workOrderService.validateAndConsumeRejectToken(id, req.getToken())) {
            throw new BizException(ErrorCode.CONFLICT, "请勿重复提交或Token已过期");
        }
        workOrderService.rejectOrder(id, userId, req.getRemark());
        return Result.ok();
    }

    @PostMapping("/{id}/assign")
    @SaCheckPermission("order:assign")
    @Operation(summary = "管理员分配工单")
    public Result<Void> assign(@Parameter(description = "工单ID") @PathVariable Long id,
                               @Valid @RequestBody AssignReq req) {
        Long operatorId = StpUtil.getLoginIdAsLong();
        workOrderService.assignOrder(id, req.getAssigneeId(), operatorId);
        return Result.ok();
    }

    @PostMapping("/{id}/manage")
    @SaCheckPermission("order:manage")
    @Operation(summary = "接管升级工单（ESCALATED_ADMIN->IN_PROGRESS，接管人=操作人）")
    public Result<Void> manageEscalated(@Parameter(description = "工单ID") @PathVariable Long id) {
        Long operatorId = StpUtil.getLoginIdAsLong();
        workOrderService.manageEscalatedOrder(id, operatorId);
        return Result.ok();
    }

    @PostMapping("/{id}/close-escalated")
    @SaCheckPermission("order:manage")
    @Operation(summary = "系统管理员强制关闭升级工单（ESCALATED_ADMIN->CLOSED）")
    public Result<Void> closeEscalated(@Parameter(description = "工单ID") @PathVariable Long id) {
        Long operatorId = StpUtil.getLoginIdAsLong();
        workOrderService.closeEscalatedOrder(id, operatorId);
        return Result.ok();
    }

    // ───────────────────── helper ─────────────────────

    private WorkOrderVO toVO(com.workorder.entity.WorkOrder order) {
        WorkOrderVO vo = new WorkOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTitle(order.getTitle());
        vo.setContent(order.getContent());
        vo.setType(order.getType());
        vo.setPriority(order.getPriority());
        vo.setStatus(order.getStatus());
        vo.setSubmitterId(order.getSubmitterId());
        vo.setAssigneeId(order.getAssigneeId());
        vo.setRejectCount(order.getRejectCount());
        vo.setMaxReject(order.getMaxReject());
        vo.setSlaDeadline(order.getSlaDeadline());
        // P5 步骤 3：**提交响应也要带分诊状态**。
        // 这里是控制器自己的 toVO（与 WorkOrderServiceImpl.toVO 是两份实现）——
        // 本轮第一次改只改了 service 那份，是**真实响应体**暴露了这个遗漏：
        // POST /api/orders 的 body 里没有 triageStatus，而列表中却有。
        // 后果会很隐蔽：前端拿到提交响应后立刻跳详情页，若用响应体渲染，就看不到"分类中"。
        vo.setTriageStatus(order.getTriageStatus());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        return vo;
    }
}
