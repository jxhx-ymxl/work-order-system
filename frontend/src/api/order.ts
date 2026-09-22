import request from '@/utils/request'
import type { WorkOrderVO, WorkOrderDetailVO, WorkOrderLogVO, SubmitOrderReq, PageQuery, RejectReq, AssignReq } from '@/types/order'
import type { PageResult } from '@/types/common'

/** 工单列表（分页+筛选） */
export function listOrders(query: PageQuery): Promise<PageResult<WorkOrderVO>> {
  return request.get<PageQuery, PageResult<WorkOrderVO>>('/orders', { params: query })
}

/** 提交工单 */
export function submitOrder(data: SubmitOrderReq): Promise<WorkOrderVO> {
  return request.post<SubmitOrderReq, WorkOrderVO>('/orders', data)
}

/** 工单详情（含操作日志） */
export function getOrderDetail(id: number): Promise<WorkOrderDetailVO> {
  return request.get<number, WorkOrderDetailVO>(`/orders/${id}`)
}

/** 工单操作日志 */
export function getOrderLogs(id: number): Promise<WorkOrderLogVO[]> {
  return request.get<number, WorkOrderLogVO[]>(`/orders/${id}/logs`)
}

/** 获取驳回操作 Token */
export function getActionToken(id: number): Promise<string> {
  return request.get<number, string>(`/orders/${id}/action-token`)
}

/** 抢单 */
export function acceptOrder(id: number): Promise<null> {
  return request.post<number, null>(`/orders/${id}/accept`)
}

/** 开始处理 */
export function startOrder(id: number): Promise<null> {
  return request.post<number, null>(`/orders/${id}/start`)
}

/** 提交验收 */
export function completeOrder(id: number): Promise<null> {
  return request.post<number, null>(`/orders/${id}/complete`)
}

/** 验收通过 */
export function approveOrder(id: number): Promise<null> {
  return request.post<number, null>(`/orders/${id}/approve`)
}

/** 验收驳回（需携带 Token） */
export function rejectOrder(id: number, data: RejectReq): Promise<null> {
  return request.post<RejectReq, null>(`/orders/${id}/reject`, data)
}

/** 管理员分配工单 */
export function assignOrder(id: number, data: AssignReq): Promise<null> {
  return request.post<AssignReq, null>(`/orders/${id}/assign`, data)
}
