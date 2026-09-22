import request from '@/utils/request'
import type { LoginReq, LoginVO, RegisterReq, User } from '@/types/user'

/** 用户登录 */
export function login(data: LoginReq): Promise<LoginVO> {
  return request.post<LoginReq, LoginVO>('/login', data)
}

/** 用户注册 */
export function register(data: RegisterReq): Promise<null> {
  return request.post<RegisterReq, null>('/users/register', data)
}

/** 根据用户名查询用户 */
export function getByUsername(username: string): Promise<User> {
  return request.get<string, User>(`/users/${encodeURIComponent(username)}`)
}
