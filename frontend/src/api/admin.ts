import request from '@/utils/request'
import type { PageResult } from '@/types/common'
import type { SlaConfig, SlaConfigUpdateReq, StatsVO, UserDetailVO, UserRoleAssignReq } from '@/types/admin'

/** 用户列表分页 */
export function listUsers(params: {
  page?: number
  size?: number
  username?: string
  deptId?: number
}): Promise<PageResult<UserDetailVO>> {
  return request.get<typeof params, PageResult<UserDetailVO>>('/admin/users', { params })
}

/** 用户详情（含角色和权限） */
export function getUserDetail(id: number): Promise<UserDetailVO> {
  return request.get<number, UserDetailVO>(`/admin/users/${id}`)
}

/** 分配用户角色 */
export function assignUserRoles(id: number, data: UserRoleAssignReq): Promise<null> {
  return request.put<UserRoleAssignReq, null>(`/admin/users/${id}/roles`, data)
}

/** 查看全部 SLA 配置 */
export function listSlaConfig(): Promise<SlaConfig[]> {
  return request.get<null, SlaConfig[]>('/admin/sla/config')
}

/** 更新 SLA 配置（根据 type+priority 定位） */
export function updateSlaConfig(data: SlaConfigUpdateReq): Promise<null> {
  return request.put<SlaConfigUpdateReq, null>('/admin/sla/config', data)
}

/** 工单统计（DEPT=部门级 / ALL=全局需 order:stats:all 权限） */
export function getOrderStats(scope: 'DEPT' | 'ALL' = 'DEPT'): Promise<StatsVO[]> {
  return request.get<{ scope: string }, StatsVO[]>('/admin/orders/stats', { params: { scope } })
}
