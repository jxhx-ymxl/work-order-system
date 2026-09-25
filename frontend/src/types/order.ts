/** AI 分诊状态 — 对齐后端 t_work_order.triage_status（P5 步骤 1） */
type TriageStatus = 'PENDING' | 'DONE' | 'FAILED'

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
  /**
   * AI 分诊状态（P5 步骤 3 前端新增）：
   * `PENDING` 待分诊（提交时缺 type/priority）/ `DONE` 已定稿 / `FAILED` 分诊失败。
   *
   * ⚠ **可选字段：当前后端 `WorkOrderVO` 尚未暴露这一列**（只在实体 `WorkOrder` 上有），
   * 因此实际响应里拿不到它 → 界面不会显示"分类中"（**不做本地猜测**，见 D61）。
   * 后端把该字段放进 VO 后，本类型与界面即可直接生效，无需再改前端。
   */
  triageStatus?: TriageStatus
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
  /**
   * 工单类型: NETWORK/UTILITY/DORM/OTHER。
   * **可不传**（P5 步骤 3 起前端不再强制）：不传 = 由系统自动判断（异步分诊），
   * 后端会先用兜底值 OTHER/普通 落库，再写回 AI 的判断结果（H4 会同时重算 sla_deadline）。
   */
  type?: string
  /** 优先级: 0普通 1紧急。**可不传**（同上，不传 = 由系统自动判断） */
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
  // P5 步骤 3：AI 异步分诊写回时会留一条 TRIAGE 日志（operator_id=0，即"系统"）。
  // 补上它是为了让"类型被机器改过"在日志时间线里可读——否则标签位置直接显示原始码 "TRIAGE"。
  TRIAGE: 'AI 分诊修正',
}

/** 工单类型映射 */
const ORDER_TYPE_MAP: Record<string, string> = {
  NETWORK: '网络故障',
  UTILITY: '水电故障',
  DORM: '宿舍与公区维修',
  OTHER: '其他',
}

/**
 * 分诊状态映射（P5 步骤 3）：`triage_status` → 界面文案 + Element Plus Tag 颜色。
 *
 * 三态的界面语义：
 * · `PENDING` → 「分类中」——**异步这件事必须可见**，否则用户看到的是兜底的 `OTHER/普通`，
 *   会以为系统判错了（演示动线第 1 步要求演出"提交后先显示分类中，数秒后变成具体类型"）；
 * · `DONE`    → 「已分类」——不额外打标，直接显示具体类型/优先级即可（打标反而喧宾夺主）；
 * · `FAILED` → 「分类失败」——需要醒目：此时页面显示的是兜底值，
 *   用户/处理人要知道"这不是 AI 的结论，而是分诊没成功"。
 *
 * ⚠ **当前后端未在 VO 里暴露该字段**，因此本映射在运行时不会被触发（见 `WorkOrderVO.triageStatus` 注释）。
 */
const TRIAGE_STATUS_MAP: Record<TriageStatus, { label: string; color: 'info' | 'success' | 'danger' }> = {
  PENDING: { label: '分类中', color: 'info' },
  DONE: { label: '已分类', color: 'success' },
  FAILED: { label: '分类失败', color: 'danger' },
}

/** 终态集合——这些状态下不显示任何操作按钮 */
const TERMINAL_STATUSES = new Set(['CLOSED', 'RELEASED', 'ESCALATED_ADMIN'])

export type {
  TriageStatus,
  WorkOrderVO,
  WorkOrderDetailVO,
  WorkOrderLogVO,
  SubmitOrderReq,
  PageQuery,
  RejectReq,
  AssignReq,
}

export { STATUS_MAP, ACTION_MAP, ORDER_TYPE_MAP, TRIAGE_STATUS_MAP, TERMINAL_STATUSES }
