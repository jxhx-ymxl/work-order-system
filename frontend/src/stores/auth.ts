import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi } from '@/api/user'
import { getByUsername } from '@/api/user'
import { getToken, setToken, removeToken } from '@/utils/token'
import type { LoginReq } from '@/types/user'
import type { RoleInfo } from '@/types/admin'

/** localStorage 持久化键名 */
const AUTH_KEYS = {
  userId: 'auth-user-id',
  username: 'auth-username',
  phone: 'auth-phone',
  deptId: 'auth-dept-id',
  roles: 'auth-roles',
  permCodes: 'auth-perm-codes',
} as const

/** 从 localStorage 恢复持久化的用户信息 */
function restoreUserInfo(): {
  userId: number | null
  username: string
  phone: string
  deptId: number | null
  roles: RoleInfo[]
  permCodes: string[]
} {
  return {
    userId: toNumberOrNull(localStorage.getItem(AUTH_KEYS.userId)),
    username: localStorage.getItem(AUTH_KEYS.username) ?? '',
    phone: localStorage.getItem(AUTH_KEYS.phone) ?? '',
    deptId: toNumberOrNull(localStorage.getItem(AUTH_KEYS.deptId)),
    roles: parseJsonSafe<RoleInfo[]>(localStorage.getItem(AUTH_KEYS.roles), []),
    permCodes: parseJsonSafe<string[]>(localStorage.getItem(AUTH_KEYS.permCodes), []),
  }
}

function toNumberOrNull(value: string | null): number | null {
  if (value === null) return null
  const num = Number(value)
  return Number.isNaN(num) ? null : num
}

function parseJsonSafe<T>(raw: string | null, fallback: T): T {
  if (!raw) return fallback
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

/** 持久化用户信息到 localStorage */
function persistUserInfo(info: {
  userId: number | null
  username: string
  phone: string
  deptId: number | null
  roles: RoleInfo[]
  permCodes: string[]
}): void {
  setOrRemove(AUTH_KEYS.userId, info.userId === null ? null : String(info.userId))
  setOrRemove(AUTH_KEYS.username, info.username || null)
  setOrRemove(AUTH_KEYS.phone, info.phone || null)
  setOrRemove(AUTH_KEYS.deptId, info.deptId === null ? null : String(info.deptId))
  setOrRemove(AUTH_KEYS.roles, info.roles.length > 0 ? JSON.stringify(info.roles) : null)
  setOrRemove(AUTH_KEYS.permCodes, info.permCodes.length > 0 ? JSON.stringify(info.permCodes) : null)
}

function setOrRemove(key: string, value: string | null): void {
  if (value !== null) {
    localStorage.setItem(key, value)
  } else {
    localStorage.removeItem(key)
  }
}

/** 清除 localStorage 中所有用户信息（供外部 401 拦截器调用） */
export function clearAuthState(): void {
  Object.values(AUTH_KEYS).forEach((key) => localStorage.removeItem(key))
  removeToken()
}

export const useAuthStore = defineStore('auth', () => {
  // ──── State ────
  const token = ref<string | null>(getToken())
  const restored = token.value
    ? restoreUserInfo()
    : { userId: null as number | null, username: '', phone: '', deptId: null as number | null, roles: [] as RoleInfo[], permCodes: [] as string[] }

  const userId = ref<number | null>(restored.userId)
  const username = ref<string>(restored.username)
  const phone = ref<string>(restored.phone)
  const deptId = ref<number | null>(restored.deptId)
  const roles = ref<RoleInfo[]>(restored.roles)
  const permCodes = ref<string[]>(restored.permCodes)

  /**
   * 标记是否已完成服务端同步初始化。
   * F5 刷新后首次进入应用时，必须用后端最新角色/权限码覆盖本地缓存，
   * 避免超管修改目标用户权限后前端死读旧数据。
   */
  const isInitialized = ref(false)

  // ──── Getters ────
  const isLoggedIn = computed(() => !!token.value)

  /** 聚合用户信息 */
  const userInfo = computed(() => ({
    id: userId.value,
    username: username.value,
    phone: phone.value,
    deptId: deptId.value,
    roles: roles.value,
    permCodes: permCodes.value,
  }))

  /** 是否为管理员（SYS_ADMIN 或 DEPT_ADMIN） */
  const isAdmin = computed(() =>
    roles.value.some((r) => r.roleCode === 'SYS_ADMIN' || r.roleCode === 'DEPT_ADMIN'),
  )

  // ──── Actions ────

  /**
   * 应用初始化时同步后端最新用户信息（角色 + 权限码）。
   *
   * 调用时机：router.beforeEach 首次导航时（isInitialized 为 false）。
   * 双端同步策略：
   *   1. GET /api/users/{username} → 最新 deptId/status（所有登录用户可用）
   *   2. GET /api/admin/users/{id}  → 最新 roles/permCodes（需管理员权限，非管理员静默回退）
   *   3. 用后端返回值强制覆盖 Pinia state + localStorage
   */
  async function initFromServer(): Promise<void> {
    // 已初始化或未登录则跳过
    if (isInitialized.value || !token.value || !username.value) {
      isInitialized.value = true
      return
    }

    try {
      await fetchUserInfo(username.value)
    } catch {
      // fetchUserInfo 内部已有静默回退——初始化失败不阻塞路由导航
    } finally {
      isInitialized.value = true
    }
  }

  /** 登录：调用 API → 持久化 token + 用户基本信息 */
  async function login(params: LoginReq): Promise<void> {
    const data = await loginApi(params)
    token.value = data.token
    setToken(data.token)
    userId.value = data.userId
    username.value = data.username
    phone.value = data.phone
    // 登录后立即拉取完整用户信息（含 deptId / roles / permCodes）
    try {
      await fetchUserInfo(data.username)
    } catch {
      // 获取扩展信息失败不阻塞登录流程
    }
    persistUserInfo({
      userId: userId.value,
      username: username.value,
      phone: phone.value,
      deptId: deptId.value,
      roles: roles.value,
      permCodes: permCodes.value,
    })
  }

  /** 获取完整用户信息：deptId、roles、permCodes 均由 GET /api/users/{username} 返回 */
  async function fetchUserInfo(name: string): Promise<void> {
    const user = await getByUsername(name)
    deptId.value = user.deptId
    roles.value = user.roles ?? []
    permCodes.value = user.permCodes ?? []

    persistUserInfo({
      userId: userId.value,
      username: username.value,
      phone: phone.value,
      deptId: deptId.value,
      roles: roles.value,
      permCodes: permCodes.value,
    })
  }

  /** 退出登录：三大清空——Pinia 状态 → localStorage 凭证 → 跳转登录页 */
  function logout(): Promise<void> {
    token.value = null
    userId.value = null
    username.value = ''
    phone.value = ''
    deptId.value = null
    roles.value = []
    permCodes.value = []
    clearAuthState()
    // 动态导入 router 避免循环依赖，失败时回退 window.location
    return import('@/router')
      .then(({ default: router }) => {
        router.push('/login')
      })
      .catch(() => {
        window.location.href = '/login'
      })
  }

  // ──── 权限判定方法 ────

  /**
   * 检查当前用户是否拥有指定权限码
   * @param permCode 权限码，如 'order:accept'
   */
  function hasPermission(permCode: string): boolean {
    return permCodes.value.includes(permCode)
  }

  /**
   * 检查当前用户是否拥有指定角色
   * @param roleCode 角色编码，如 'SYS_ADMIN'
   */
  function hasRole(roleCode: string): boolean {
    return roles.value.some((r) => r.roleCode === roleCode)
  }

  return {
    // state
    token,
    userId,
    username,
    phone,
    deptId,
    roles,
    permCodes,
    // getters
    isLoggedIn,
    userInfo,
    isAdmin,
    // actions
    login,
    fetchUserInfo,
    initFromServer,
    logout,
    // permissions
    hasPermission,
    hasRole,
    // lifecycle
    isInitialized,
  }
})
