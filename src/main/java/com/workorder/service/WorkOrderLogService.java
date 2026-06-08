package com.workorder.service;

import com.workorder.common.vo.WorkOrderLogVO;

import java.util.List;

public interface WorkOrderLogService {

    void saveLog(Long orderId, String orderNo, Long operatorId, String action,
                 String oldStatus, String newStatus, String remark);

    List<WorkOrderLogVO> queryLogs(Long orderId);
}
