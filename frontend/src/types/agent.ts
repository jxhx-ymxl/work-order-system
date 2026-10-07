/**
 * 工单调查助手（同步接口）类型。
 *
 * 对齐后端 `AgentInvestigationReq` / `AgentInvestigationVO`（`docs/AGENT-PLAN.md` §3.2），
 * 接口为 `POST /api/agent/investigations`。
 *
 * ⚠ `frontend/api-docs.json` 里**没有**这个端点：controller 带
 * `@ConditionalOnProperty("agent.investigation.enabled")` 且**默认关**——关闭时该 bean 不存在、
 * 路径表现为 404。因此这里的类型**以后端 VO 字段为准**（`AgentInvestigationVO`，逐字段核对过），
 * 不以 `api-docs.json` 为源。
 */

/** 调查请求 — 对齐 AgentInvestigationReq（两个字段都 @NotBlank） */
interface AgentInvestigationReq {
  /** 起点工单编号（结构化入参，后端不从问题文本里解析） */
  orderNo: string
  /** 自然语言问题（仅用于分类与向模型提问） */
  question: string
}

/**
 * 调查结果 — 对齐 AgentInvestigationVO（`Result<AgentInvestigationVO>` 的 `data`）。
 *
 * 后端 `spring.jackson.default-property-inclusion=non_null`：没有值的字段**整个键都不下发**，
 * 所以除 `status` 外全部按可选处理（`report == null` 时 `problemType` / 证据 / 建议号都不出现）。
 */
interface AgentInvestigationVO {
  /** 终态：COMPLETED / INCOMPLETE / FAILED / TIMED_OUT / CANCELLED */
  status: string
  /** 失败 / 终止原因码（成功时为 null 或不下发） */
  failureCode?: string | null
  /** 问题类型（无报告时为 null 或不下发） */
  problemType?: string | null
  /** 报告引用的证据编号（只有编号，报告正文由后端渲染） */
  evidenceIds?: string[] | null
  /** 报告引用的建议编号 */
  suggestionIds?: string[] | null
  /** 后端按证据渲染的三段正文；无报告时为 null 或不下发 */
  renderedText?: string | null
}

/**
 * 状态 → 中文文案 + Tag 颜色（与 `types/order.ts` 的 STATUS_MAP 同一做法）。
 *
 * 与 `types/order.ts` 里 `TRIAGE_STATUS_MAP` 同规矩：**只映射后端真的会返回的取值**，
 * 遇到表外的状态原样展示（`status` 不做本地猜测，避免把未知状态伪装成已知状态）。
 */
const AGENT_STATUS_MAP: Record<string, { label: string; color: 'success' | 'warning' | 'danger' | 'info' }> = {
  // ⚠ 文案是「调查已完成」而不是「已完成」：这个标签指的是**调查终态** `COMPLETED`，
  // 与工单状态是两回事。2026-10-07 服务器走查发现：工单本身是「已释放」，两个标签并排时
  // 「已完成」会被读成"工单已完成"（D105 发现项①）。
  COMPLETED: { label: '调查已完成', color: 'success' },
  INCOMPLETE: { label: '未完成', color: 'warning' },
  FAILED: { label: '失败', color: 'danger' },
  TIMED_OUT: { label: '超时', color: 'danger' },
  CANCELLED: { label: '已取消', color: 'info' },
}

export type { AgentInvestigationReq, AgentInvestigationVO }
export { AGENT_STATUS_MAP }
