import axios, { type AxiosError, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { getToken } from './token'

/** 清除所有 auth 相关的 localStorage 键（避免循环依赖 auth store） */
function clearAllAuthState(): void {
  const keys = [
    'sa-token',
    'auth-user-id',
    'auth-username',
    'auth-phone',
    'auth-dept-id',
    'auth-roles',
    'auth-perm-codes',
  ]
  keys.forEach((key) => localStorage.removeItem(key))
}

/** 创建 Axios 实例，baseURL 统一为 /api */
const request = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

/**
 * 请求拦截器 — 自动注入 Sa-Token Authorization header
 */
request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = token
    }
    return config
  },
  (error: AxiosError) => {
    return Promise.reject(error)
  },
)

/** 后端统一响应结构 */
interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

/**
 * 响应拦截器 — 三层防线：
 * 1. 自动解包 response.data → 提取 .data 字段
 * 2. code !== 200 → ElMessage.error + Promise.reject
 * 3. code === 401 → 清除 token + 重定向 /login
 */
request.interceptors.response.use(
  (response) => {
    const res = response.data as ApiResponse

    // 成功：解包后透传 data 字段（类型桥接满足 Axios 拦截器签名）
    if (res.code === 200) {
      return res.data as unknown as AxiosResponse
    }

    // 401 未登录/Token 过期
    if (res.code === 401) {
      ElMessage.error(res.message || '登录已过期，请重新登录')
      clearAllAuthState()
      // 同步重置 Pinia auth store 内存状态（避免 UI 显示残留登录态）
      import('@/stores/auth').then(({ useAuthStore }) => {
        const store = useAuthStore()
        store.token = null
        store.userId = null
        store.username = ''
        store.phone = ''
        store.deptId = null
        store.roles = []
        store.permCodes = []
      }).catch(() => { /* 静默回退——store 重置非关键路径 */ })
      // 动态导入 router 避免循环依赖，失败时回退 window.location
      import('@/router')
        .then(({ default: router }) => {
          router.push('/login')
        })
        .catch(() => {
          window.location.href = '/login'
        })
      return Promise.reject(new Error(res.message || '未授权')) as unknown as AxiosResponse
    }

    // 其他业务错误
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败')) as unknown as AxiosResponse
  },
  (error: AxiosError) => {
    // 网络异常 / 超时
    const message = error.message || '网络异常，请稍后重试'
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

export default request
