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

    WorkOrderDetailVO getOrderDetail(Long orderId);

    List<StatsVO> getStats(String scope, Long currentUserId);

    void acceptOrder(Long orderId, Long userId);

    void startOrder(Long orderId, Long operatorId);

    void completeOrder(Long orderId, Long operatorId);

    void approveOrder(Long orderId, Long operatorId);

    void rejectOrder(Long orderId, Long operatorId, String remark);

    void releaseOrder(Long orderId);

    void assignOrder(Long orderId, Long assigneeId, Long operatorId);

    /** Issue #32: 生成驳回一次性 Token，有效期 30 秒 */
    String generateRejectToken(Long orderId);

    /** Issue #32: Lua 脚本原子校验并消费 Token，返回 true 表示通过 */
    boolean validateAndConsumeRejectToken(Long orderId, String token);
}
