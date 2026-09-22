/** 工单视图对象 — 对齐 api-docs.json WorkOrderVO */
interface WorkOrderVO {
  /** 工单主键ID */
  id: number
  /** 工单编号 WO-YYYYMMDD-XXXXX */
  orderNo: string
  /** 工单标题 */
  title: string
  /** 工单内容 */
  content: string
  /** 工单类型: NETWORK/UTILITY/DORM/OTHER */
  type: string
  /** 优先级: 0普通 1紧急 */
  priority: number
  /** 当前状态 */
  status: string
  /** 提交人ID */
  submitterId: number
  /** 处理人ID */
  assigneeId: number
  /** 驳回次数 */
  rejectCount: number
  /** 最大驳回次数 */
  maxReject: number
  /** SLA截止时间 */
  slaDeadline: string
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 工单详情视图 — 对齐 api-docs.json WorkOrderDetailVO */
interface WorkOrderDetailVO {
  /** 工单基本信息 */
  order: WorkOrderVO
  /** 操作日志列表(按时间正序) */
  logs: WorkOrderLogVO[]
}

/** 工单操作日志视图 — 对齐 api-docs.json WorkOrderLogVO */
interface WorkOrderLogVO {
  /** 日志ID */
  id: number
  /** 关联工单ID */
  orderId: number
  /** 工单编号 */
  orderNo: string
  /** 操作人ID */
  operatorId: number
  /** 操作人姓名 */
  operatorName: string
  /** 操作类型: SUBMIT/ACCEPT/START/COMPLETE/APPROVE/REJECT/ASSIGN/RELEASE */
  action: string
  /** 变更前状态 */
  oldStatus: string
  /** 变更后状态 */
  newStatus: string
  /** 备注 */
  remark: string
  /** 操作时间 */
  createdAt: string
}

/** 工单提交请求 — 对齐 api-docs.json SubmitOrderReq */
interface SubmitOrderReq {
  /** 工单标题 */
  title: string
  /** 工单内容 */
  content: string
  /** 工单类型: NETWORK/UTILITY/DORM/OTHER */
  type: string
  /** 优先级: 0普通 1紧急 */
  priority?: number
}

/** 分页查询参数 — 对齐 api-docs.json PageQuery */
interface PageQuery {
  /** 页码 */
  page?: number
  /** 每页条数(最大100) */
  size?: number
  /** 工单状态筛选 */
  status?: string
  /** 工单编号模糊搜索 */
  orderNo?: string
  /** 提交人ID筛选 */
  submitterId?: number
  /** 处理人ID筛选 */
  assigneeId?: number
}

/** 驳回请求 — 对齐 api-docs.json RejectReq */
interface RejectReq {
  /** 操作Token（通过 GET /api/orders/{id}/action-token 获取） */
  token: string
  /** 驳回理由 */
  remark: string
}

/** 分配工单请求 — 对齐 api-docs.json AssignReq */
interface AssignReq {
  /** 被指派人用户ID */
  assigneeId: number
}

/** Element Plus el-tag type 属性接受的合法颜色值 */
type TagColor = 'primary' | 'success' | 'warning' | 'info' | 'danger' | undefined

/** 状态映射：状态值 → 中文标签 + Element Plus Tag 颜色 */
const STATUS_MAP: Record<string, { label: string; color: TagColor }> = {
  PENDING: { label: '待分配', color: 'info' },
  ACCEPTED: { label: '已接单', color: undefined },
  IN_PROGRESS: { label: '处理中', color: 'warning' },
  AWAIT_APPROVAL: { label: '待验收', color: 'warning' },
  CLOSED: { label: '已关闭', color: 'success' },
  RELEASED: { label: '已释放', color: 'danger' },
  ESCALATED_ADMIN: { label: '已升级', color: 'danger' },
}

/** 操作类型映射：操作码 → 中文描述 */
const ACTION_MAP: Record<string, string> = {
  SUBMIT: '提交工单',
  ACCEPT: '接单',
  START: '开始处理',
  COMPLETE: '提交验收',
  APPROVE: '验收通过',
  REJECT: '驳回',
  ASSIGN: '分配工单',
  RELEASE: '超时释放',
}

/** 工单类型映射 */
const ORDER_TYPE_MAP: Record<string, string> = {
  NETWORK: '网络故障',
  UTILITY: '水电故障',
  DORM: '宿舍与公区维修',
  OTHER: '其他',
}

/** 终态集合——这些状态下不显示任何操作按钮 */
const TERMINAL_STATUSES = new Set(['CLOSED', 'RELEASED', 'ESCALATED_ADMIN'])

export type {
  WorkOrderVO,
  WorkOrderDetailVO,
  WorkOrderLogVO,
  SubmitOrderReq,
  PageQuery,
  RejectReq,
  AssignReq,
}

export { STATUS_MAP, ACTION_MAP, ORDER_TYPE_MAP, TERMINAL_STATUSES }
