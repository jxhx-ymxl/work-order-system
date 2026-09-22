/** 角色 — 对齐 api-docs.json Role */
interface Role {
  /** 角色ID */
  id: number
  /** 角色编码: SUBMITTER/HANDLER/DEPT_ADMIN/SYS_ADMIN */
  roleCode: string
  /** 角色名称 */
  roleName: string
  /** 备注 */
  remark: string
}

/** 角色视图对象 — 对齐 api-docs.json RoleVO */
interface RoleVO {
  /** 角色ID */
  id: number
  /** 角色编码 */
  roleCode: string
  /** 角色名称 */
  roleName: string
  /** 备注 */
  remark: string
  /** 已分配的权限ID列表 */
  permIds: number[]
}

/** 角色创建请求 — 对齐 api-docs.json RoleCreateReq */
interface RoleCreateReq {
  /** 角色编码 */
  roleCode: string
  /** 角色名称 */
  roleName: string
  /** 备注 */
  remark?: string
}

/** 角色更新请求 — 对齐 api-docs.json RoleUpdateReq */
interface RoleUpdateReq {
  /** 角色名称 */
  roleName: string
  /** 备注 */
  remark?: string
}

/** 角色权限分配请求 — 对齐 api-docs.json RolePermissionAssignReq */
interface RolePermissionAssignReq {
  /** 权限ID列表 */
  permIds: number[]
}

/** 权限树节点 — 对齐 api-docs.json PermissionTreeVO */
interface PermissionTreeVO {
  /** 权限ID */
  id: number
  /** 权限编码 */
  permCode: string
  /** 权限名称 */
  permName: string
  /** 父权限ID */
  parentId: number
  /** 子权限列表 */
  children: PermissionTreeVO[]
}

/** SLA 配置 — 对齐 api-docs.json SlaConfig */
interface SlaConfig {
  /** 配置ID */
  id: number
  /** 工单类型 */
  type: string
  /** 优先级: 0普通 1紧急 */
  priority: number
  /** 接单时限（分钟） */
  acceptMinutes: number
  /** 完成时限（分钟） */
  finishMinutes: number
}

/** SLA 配置更新请求 — 对齐 api-docs.json SlaConfigUpdateReq */
interface SlaConfigUpdateReq {
  /** 工单类型 */
  type: string
  /** 优先级: 0普通 1紧急 */
  priority: number
  /** 接单时限（分钟） */
  acceptMinutes: number
  /** 完成时限（分钟） */
  finishMinutes: number
}

/** 工单统计视图 — 对齐 api-docs.json StatsVO */
interface StatsVO {
  /** 工单状态 */
  status: string
  /** 数量 */
  count: number
}

/** 用户角色分配请求 — 对齐 api-docs.json UserRoleAssignReq */
interface UserRoleAssignReq {
  /** 角色ID列表 */
  roleIds: number[]
}

/** 角色简要信息 — 对齐 api-docs.json RoleInfo */
interface RoleInfo {
  /** 角色编码 */
  roleCode: string
  /** 角色名称 */
  roleName: string
}

/** 用户详情视图对象 — 对齐 api-docs.json UserDetailVO */
interface UserDetailVO {
  /** 用户ID */
  id: number
  /** 用户名 */
  username: string
  /** 手机号 */
  phone: string
  /** 所属部门ID */
  deptId: number
  /** 状态: 1启用 0禁用 */
  status: number
  /** 创建时间 */
  createdAt: string
  /** 角色列表 */
  roles: RoleInfo[]
  /** 权限码列表 */
  permCodes: string[]
}

/** 种子角色编码——不允许删除 */
const SEED_ROLES = ['SUBMITTER', 'HANDLER', 'DEPT_ADMIN', 'SYS_ADMIN'] as const

/** 权限码常量 */
const PERM_CODES = {
  ORDER_ACCEPT: 'order:accept',
  ORDER_START: 'order:start',
  ORDER_COMPLETE: 'order:complete',
  ORDER_APPROVE: 'order:approve',
  ORDER_REJECT: 'order:reject',
  ORDER_ASSIGN: 'order:assign',
  ORDER_STATS: 'order:stats',
  ORDER_STATS_ALL: 'order:stats:all',
  SYSTEM_USER_MANAGE: 'system:user:manage',
  SYSTEM_ROLE_MANAGE: 'system:role:manage',
  SLA_CONFIG_MANAGE: 'sla:config:manage',
} as const

export type {
  Role,
  RoleVO,
  RoleCreateReq,
  RoleUpdateReq,
  RolePermissionAssignReq,
  PermissionTreeVO,
  SlaConfig,
  SlaConfigUpdateReq,
  StatsVO,
  UserRoleAssignReq,
  RoleInfo,
  UserDetailVO,
}

export { SEED_ROLES, PERM_CODES }
