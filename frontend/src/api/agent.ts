import axios, { type AxiosError } from 'axios'
import { getToken } from '@/utils/token'
import type { Result } from '@/types/common'
import type { AgentInvestigationReq, AgentInvestigationVO } from '@/types/agent'

/**
 * 调查接口**专用** Axios 实例（`docs/AGENT-PLAN.md` §4.1 / `docs/DECISIONS.md` D89）。
 *
 * 两处刻意的不同，**都不动 `@/utils/request.ts` 的全局实例（含那边的 15000ms）**：
 *
 * 1. `timeout: 90000` —— 只为 `POST /api/agent/investigations` 单独配这一档。
 *    它是**同步**接口：端到端实测中位约 11s、最长约 30s（D89，样本 6 次，非生产数字），
 *    用全局的 15s 会让前端在服务端还在跑时就先断（用户看到失败，服务端仍在跑）。
 * 2. **不做 `code !== 200` 的自动 toast + reject** —— 五种拒绝（401/403/409/429/503）与
 *    `INCOMPLETE` / `CANCELLED` 的身份都在**业务 code** 里（HTTP 一律 200），
 *    全局拦截器"解包 + 统一报错"会把按 code 分支所需的信息吃掉。这里把
 *    `Result` 原样交给页面，由页面按业务 code 分别处理。
 */
const AGENT_INVESTIGATION_TIMEOUT_MS = 90000

const agentRequest = axios.create({
  baseURL: '/api',
  timeout: AGENT_INVESTIGATION_TIMEOUT_MS,
})

/** 请求拦截器：与全局实例同款——从 localStorage 取 token，按 Sa-Token 格式放进 Authorization */
agentRequest.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

/**
 * 传输层结果：把"业务 code"与"请求根本没拿到业务响应"分开。
 *
 * 有了这一层，页面才不必用 `AxiosError` 反推业务语义（这也是"D24：HTTP 200 不等于成功"的同一条纪律）。
 */
type AgentCallResult =
  /** HTTP 层成功，拿到完整 Result（业务 code 可能是 200，也可能是 401/403/409/429/503/500） */
  | { kind: 'response'; result: Result<AgentInvestigationVO> }
  /** 功能未启用：controller bean 不存在 → 无匹配 handler → HTTP 404（**不是**错误提示的场景） */
  | { kind: 'disabled' }
  /** 90s 超时：同步接口没在超时链内返回 */
  | { kind: 'timeout' }
  /** 网络层失败（连不上 / 代理异常等） */
  | { kind: 'network'; message: string }

/**
 * 发起一次调查（同步）。
 *
 * **不抛异常**：所有分支都收敛成 {@link AgentCallResult}，页面按 `kind` + 业务 code 分支，
 * 避免"传输层失败"和"业务拒绝"混在同一条 catch 里。
 */
export async function investigateAgent(data: AgentInvestigationReq): Promise<AgentCallResult> {
  try {
    const response = await agentRequest.post<Result<AgentInvestigationVO>>(
      '/agent/investigations',
      data,
    )
    return { kind: 'response', result: response.data }
  } catch (error) {
    const err = error as AxiosError

    // 开关默认关时后端 bean 不存在：HTTP 404（GlobalExceptionHandler 的"路径不存在"分支）。
    // 这是"功能没开"，要按提示呈现，不能报错。
    if (err.response?.status === 404) {
      return { kind: 'disabled' }
    }

    // axios 的超时：代码固定为 ECONNABORTED（message 里带 timeout of 90000ms exceeded）
    if (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT') {
      return { kind: 'timeout' }
    }

    // 其余非 2xx：例如经 Nginx 时代理先断（`deploy/nginx.conf` 给 /api/agent/ 配的是 75s，
    // 比前端的 90s 早），返回 504/502。这类响应**没有业务体**，只能说清"是谁返回的什么码"，
    // 不能把它伪装成业务拒绝，也不要直接把 axios 的英文 message 甩给用户。
    if (err.response) {
      return { kind: 'network', message: `服务端返回 HTTP ${err.response.status}（非业务响应）` }
    }

    return { kind: 'network', message: err.message || '网络异常，请稍后重试' }
  }
}

export type { AgentCallResult }
