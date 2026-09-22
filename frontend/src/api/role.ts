import request from '@/utils/request'
import type { Role, RoleVO, RoleCreateReq, RoleUpdateReq, RolePermissionAssignReq, PermissionTreeVO } from '@/types/admin'

/** 角色列表 */
export function listRoles(): Promise<Role[]> {
  return request.get<null, Role[]>('/admin/roles')
}

/** 角色详情（含已分配权限ID列表） */
export function getRoleDetail(id: number): Promise<RoleVO> {
  return request.get<number, RoleVO>(`/admin/roles/${id}`)
}

/** 创建角色 */
export function createRole(data: RoleCreateReq): Promise<Role> {
  return request.post<RoleCreateReq, Role>('/admin/roles', data)
}

/** 更新角色 */
export function updateRole(id: number, data: RoleUpdateReq): Promise<Role> {
  return request.put<RoleUpdateReq, Role>(`/admin/roles/${id}`, data)
}

/** 删除角色 */
export function deleteRole(id: number): Promise<null> {
  return request.delete<number, null>(`/admin/roles/${id}`)
}

/** 为角色分配权限 */
export function assignPermissions(id: number, data: RolePermissionAssignReq): Promise<null> {
  return request.put<RolePermissionAssignReq, null>(`/admin/roles/${id}/permissions`, data)
}

/** 权限树 */
export function getPermissionTree(): Promise<PermissionTreeVO[]> {
  return request.get<null, PermissionTreeVO[]>('/admin/permissions/tree')
}
