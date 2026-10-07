/**
 * 部门（2026-10-08 部门实体化）——对齐后端 `DeptVO` / `DeptOptionVO`。
 *
 * 模型选择：用户与工单**继续只存 dept_id 这个数字**，部门表只做"id → 名称 / 启停"；
 * 「删除」= `enabled = 0`（不做物理删除），改名对历史数据**自动生效**。
 */

/** 管理端视图（含停用项；`userCount` 是"停用提示"的来源） */
interface DeptVO {
  id: number
  name: string
  /** 1 启用 / 0 停用 */
  enabled: number
  /** 该部门下的用户数 */
  userCount: number
  createdAt: string
  updatedAt: string
}

/** 公开下拉项（`GET /api/depts`，免登录）：只有 id 与名称，只含启用项 */
interface DeptOption {
  id: number
  name: string
}

export type { DeptVO, DeptOption }
