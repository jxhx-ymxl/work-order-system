import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import { listUsers, assignUserRoles, listSlaConfig, updateSlaConfig, getOrderStats } from '@/api/admin'
import {
  listRoles,
  getRoleDetail,
  createRole,
  updateRole,
  deleteRole,
  assignPermissions,
  getPermissionTree,
} from '@/api/role'
import type {
  UserDetailVO,
  Role,
  RoleVO,
  RoleCreateReq,
  RoleUpdateReq,
  PermissionTreeVO,
  SlaConfig,
  SlaConfigUpdateReq,
  StatsVO,
} from '@/types/admin'
import type { PageResult } from '@/types/common'

export const useAdminStore = defineStore('admin', () => {
  // ──── Users State ────
  const users = ref<UserDetailVO[]>([])
  const loading = ref(false)

  const pagination = reactive({
    total: 0,
    pages: 0,
    current: 1,
    size: 10,
  })

  const filters = reactive({
    username: '',
    deptId: null as number | null,
  })

  // ──── Roles State ────
  const allRoles = ref<Role[]>([])
  const rolesLoading = ref(false)

  // ──── Permission Tree State ────
  const permTree = ref<PermissionTreeVO[]>([])
  const permTreeLoading = ref(false)

  // ──── SLA State ────
  const slaConfigs = ref<SlaConfig[]>([])
  const slaLoading = ref(false)
  const slaSaving = ref(false)

  // ──── Stats State ────
  const statsData = ref<StatsVO[]>([])
  const statsLoading = ref(false)
  const statsScope = ref<'DEPT' | 'ALL'>('DEPT')

  // ──── Users Actions ────

  /** 获取用户分页列表 */
  async function fetchUsers(page?: number, size?: number): Promise<void> {
    loading.value = true
    try {
      const params: { page: number; size: number; username?: string; deptId?: number } = {
        page: page ?? pagination.current,
        size: size ?? pagination.size,
      }
      if (filters.username) {
        params.username = filters.username
      }
      if (filters.deptId !== null) {
        params.deptId = filters.deptId
      }

      const result: PageResult<UserDetailVO> = await listUsers(params)
      users.value = result.records ?? []
      pagination.total = result.total
      pagination.pages = result.pages
      pagination.current = result.current
      if (size !== undefined) {
        pagination.size = size
      }
    } finally {
      loading.value = false
    }
  }

  /** 获取全部角色列表（供分配弹窗使用） */
  async function fetchAllRoles(): Promise<void> {
    rolesLoading.value = true
    try {
      allRoles.value = await listRoles()
    } finally {
      rolesLoading.value = false
    }
  }

  /** 分配用户角色 */
  async function performAssignRoles(userId: number, roleIds: number[]): Promise<void> {
    await assignUserRoles(userId, { roleIds })
  }

  /** 重置筛选条件 */
  function resetFilters(): void {
    filters.username = ''
    filters.deptId = null
  }

  // ──── Roles Actions ────

  /** 获取角色列表 */
  async function fetchRoles(): Promise<Role[]> {
    rolesLoading.value = true
    try {
      const roles = await listRoles()
      allRoles.value = roles
      return roles
    } finally {
      rolesLoading.value = false
    }
  }

  /** 获取角色详情（含已分配权限ID列表） */
  async function fetchRoleDetail(id: number): Promise<RoleVO> {
    return await getRoleDetail(id)
  }

  /** 创建角色 */
  async function performCreateRole(data: RoleCreateReq): Promise<Role> {
    const role = await createRole(data)
    return role
  }

  /** 更新角色 */
  async function performUpdateRole(id: number, data: RoleUpdateReq): Promise<Role> {
    const role = await updateRole(id, data)
    return role
  }

  /** 删除角色 */
  async function performDeleteRole(id: number): Promise<void> {
    await deleteRole(id)
  }

  /** 获取权限树 */
  async function fetchPermissionTree(): Promise<PermissionTreeVO[]> {
    permTreeLoading.value = true
    try {
      const tree = await getPermissionTree()
      permTree.value = tree
      return tree
    } finally {
      permTreeLoading.value = false
    }
  }

  /** 为角色分配权限 */
  async function performAssignPermissions(id: number, permIds: number[]): Promise<void> {
    await assignPermissions(id, { permIds })
  }

  // ──── SLA Actions ────

  /** 获取全部 SLA 配置 */
  async function fetchSlaConfig(): Promise<void> {
    slaLoading.value = true
    try {
      slaConfigs.value = await listSlaConfig()
    } finally {
      slaLoading.value = false
    }
  }

  /** 更新单条 SLA 配置 */
  async function performUpdateSlaConfig(data: SlaConfigUpdateReq): Promise<void> {
    slaSaving.value = true
    try {
      await updateSlaConfig(data)
      // 更新本地缓存
      const idx = slaConfigs.value.findIndex(
        (c) => c.type === data.type && c.priority === data.priority,
      )
      if (idx !== -1) {
        slaConfigs.value[idx] = { ...slaConfigs.value[idx], ...data }
      }
    } finally {
      slaSaving.value = false
    }
  }

  // ──── Stats Actions ────

  /** 获取工单统计数据 */
  async function fetchStats(scope: 'DEPT' | 'ALL'): Promise<void> {
    statsLoading.value = true
    statsScope.value = scope
    try {
      statsData.value = await getOrderStats(scope)
    } finally {
      statsLoading.value = false
    }
  }

  return {
    // state
    users,
    loading,
    pagination,
    filters,
    allRoles,
    rolesLoading,
    permTree,
    permTreeLoading,
    // sla state
    slaConfigs,
    slaLoading,
    slaSaving,
    // stats state
    statsData,
    statsLoading,
    statsScope,
    // users actions
    fetchUsers,
    fetchAllRoles,
    performAssignRoles,
    resetFilters,
    // roles actions
    fetchRoles,
    fetchRoleDetail,
    performCreateRole,
    performUpdateRole,
    performDeleteRole,
    fetchPermissionTree,
    performAssignPermissions,
    // sla actions
    fetchSlaConfig,
    performUpdateSlaConfig,
    // stats actions
    fetchStats,
  }
})
