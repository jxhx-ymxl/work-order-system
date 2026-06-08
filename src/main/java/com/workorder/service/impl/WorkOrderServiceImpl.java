package com.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.PageResult;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderLogService;
import com.workorder.service.WorkOrderService;
import com.workorder.utils.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkOrderServiceImpl implements WorkOrderService {

    private final WorkOrderMapper workOrderMapper;
    private final SlaConfigMapper slaConfigMapper;
    private final WorkOrderLogService workOrderLogService;
    private final OrderNoGenerator orderNoGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder submitOrder(SubmitOrderReq req, Long submitterId) {
        String orderNo = orderNoGenerator.next();

        Integer priority = req.getPriority() != null ? req.getPriority() : 0;
        SlaConfig slaConfig = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, req.getType())
                .eq(SlaConfig::getPriority, priority));

        LocalDateTime slaDeadline = null;
        if (slaConfig != null && slaConfig.getFinishMinutes() != null) {
            slaDeadline = LocalDateTime.now().plusMinutes(slaConfig.getFinishMinutes());
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(orderNo);
        order.setTitle(req.getTitle());
        order.setContent(req.getContent());
        order.setType(req.getType());
        order.setPriority(priority);
        order.setStatus("PENDING");
        order.setSubmitterId(submitterId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setSlaDeadline(slaDeadline);
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(order);

        workOrderLogService.saveLog(order.getId(), orderNo, submitterId,
                "SUBMIT", null, "PENDING", null);

        return order;
    }

    @Override
    public PageResult<WorkOrderVO> listOrders(PageQuery query, Long currentUserId) {
        Page<WorkOrder> page = new Page<>(query.getPage(), query.getSize());
        IPage<WorkOrder> result = workOrderMapper.selectPageWithConditions(
                page, query.getStatus(), query.getOrderNo(),
                query.getSubmitterId(), query.getAssigneeId());

        List<WorkOrderVO> vos = result.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        return PageResult.of(result.getTotal(), result.getPages(), result.getCurrent(), vos);
    }

    @Override
    public WorkOrderDetailVO getOrderDetail(Long orderId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在");
        }

        List<WorkOrderLogVO> logs = workOrderLogService.queryLogs(orderId);

        WorkOrderDetailVO detail = new WorkOrderDetailVO();
        detail.setOrder(toVO(order));
        detail.setLogs(logs);
        return detail;
    }

    private WorkOrderVO toVO(WorkOrder order) {
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
