/** 用户登录请求 */
interface LoginReq {
  username: string
  password: string
}

/** 登录响应 */
interface LoginVO {
  /** Sa-Token 令牌 */
  token: string
  /** 用户ID */
  userId: number
  /** 用户名 */
  username: string
  /** 手机号 */
  phone: string
}

/** 用户注册请求 */
interface RegisterReq {
  /** 用户名 */
  username: string
  /** 密码 */
  password: string
  /** 手机号 */
  phone?: string
  /** 所属部门ID */
  deptId?: number
}

/** 角色简要信息 */
interface RoleInfo {
  roleCode: string
  roleName: string
}

/** 用户基本信息（含角色和权限码——对齐后端 UserDetailVO） */
interface User {
  id: number
  username: string
  phone: string
  deptId: number
  status: number
  createdAt: string
  /** 角色列表 */
  roles: RoleInfo[]
  /** 权限码列表 */
  permCodes: string[]
}

export type { LoginReq, LoginVO, RegisterReq, User, RoleInfo }
