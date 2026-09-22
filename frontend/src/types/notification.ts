/** 站内信 — 对齐 t_notification 表 */
interface Notification {
  /** 通知ID */
  id: number
  /** 接收人ID */
  userId: number
  /** 通知标题 */
  title: string
  /** 通知内容 */
  content: string
  /** 关联类型: ORDER/SYSTEM */
  refType: string
  /** 关联ID(工单ID等) */
  refId: number
  /** 0未读 1已读 */
  isRead: number
  /** 创建时间 */
  createdAt: string
}

/** 未读计数响应 */
interface UnreadCount {
  /** 未读数量 */
  count: number
}

export type { Notification, UnreadCount }
