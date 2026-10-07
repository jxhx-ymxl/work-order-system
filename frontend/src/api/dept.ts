import request from '@/utils/request'
import type { DeptVO, DeptOption } from '@/types/dept'

/**
 * 部门接口（2026-10-08）。
 *
 * 两组权限面不同：`/admin/depts` 需要 `system:dept:manage`；`/depts` 是**免登录只读**（注册页下拉用）。
 * 两者都走全局 axios 实例（普通接口，不吃调查接口那个 90s 独立实例）。
 */

/** 管理端：部门列表（含停用项，带 userCount） */
export function listDepts(): Promise<DeptVO[]> {
  return request.get<null, DeptVO[]>('/admin/depts')
}

/** 管理端：新增部门（重名 → 业务码 400） */
export function createDept(data: { name: string }): Promise<DeptVO> {
  return request.post<{ name: string }, DeptVO>('/admin/depts', data)
}

/** 管理端：改名 / 启停（两个字段都可选；**没有删除**，停用即"删除"） */
export function updateDept(id: number, data: { name?: string; enabled?: number }): Promise<DeptVO> {
  return request.put<{ name?: string; enabled?: number }, DeptVO>(`/admin/depts/${id}`, data)
}

/** 公开只读：启用的部门下拉（免登录） */
export function listDeptOptions(): Promise<DeptOption[]> {
  return request.get<null, DeptOption[]>('/depts')
}
