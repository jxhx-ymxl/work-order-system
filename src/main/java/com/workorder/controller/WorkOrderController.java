package com.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.workorder.common.PageResult;
import com.workorder.common.Result;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(summary = "工单详情")
    public Result<WorkOrderDetailVO> detail(@PathVariable Long id) {
        return Result.ok(workOrderService.getOrderDetail(id));
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "工单操作日志")
    public Result<List<WorkOrderLogVO>> logs(@PathVariable Long id) {
        return Result.ok(workOrderLogService.queryLogs(id));
    }

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
        vo.setCreatedAt(order.getCreatedAt());
        vo.setUpdatedAt(order.getUpdatedAt());
        return vo;
    }
}
