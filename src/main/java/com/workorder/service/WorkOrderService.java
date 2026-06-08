package com.workorder.service;

import com.workorder.common.PageResult;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.WorkOrderDetailVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.WorkOrder;

public interface WorkOrderService {

    WorkOrder submitOrder(SubmitOrderReq req, Long submitterId);

    PageResult<WorkOrderVO> listOrders(PageQuery query, Long currentUserId);

    WorkOrderDetailVO getOrderDetail(Long orderId);
}
