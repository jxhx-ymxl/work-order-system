import request from '@/utils/request'
import type { Notification, UnreadCount } from '@/types/notification'
import type { PageResult } from '@/types/common'

/** 站内信列表 */
export function listNotifications(params: {
  page?: number
  size?: number
}): Promise<PageResult<Notification>> {
  return request.get<typeof params, PageResult<Notification>>('/notifications', { params })
}

/** 标记已读 */
export function markAsRead(id: number): Promise<null> {
  return request.put<number, null>(`/notifications/${id}/read`)
}

/** 未读计数 */
export function getUnreadCount(): Promise<UnreadCount> {
  return request.get<null, UnreadCount>('/notifications/unread-count')
}
