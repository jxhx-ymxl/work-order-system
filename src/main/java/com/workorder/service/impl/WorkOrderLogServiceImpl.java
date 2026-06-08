package com.workorder.service.impl;

import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.service.WorkOrderLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderLogServiceImpl implements WorkOrderLogService {

    private final WorkOrderLogMapper workOrderLogMapper;

    @Override
    public void saveLog(Long orderId, String orderNo, Long operatorId, String action,
                        String oldStatus, String newStatus, String remark) {
        WorkOrderLog log = new WorkOrderLog();
        log.setOrderId(orderId);
        log.setOrderNo(orderNo);
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setOldStatus(oldStatus);
        log.setNewStatus(newStatus);
        log.setRemark(remark);
        log.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(log);
    }

    @Override
    public List<WorkOrderLogVO> queryLogs(Long orderId) {
        return workOrderLogMapper.selectByOrderId(orderId);
    }
}
