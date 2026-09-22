/** 后端统一响应结构 */
interface Result<T> {
  /** 状态码: 200=成功, 400=参数错误, 401=未登录, 403=无权限, 404=不存在, 409=状态冲突, 500=服务器错误 */
  code: number
  /** 提示信息 */
  message: string
  /** 响应数据 */
  data: T
}

/** 分页结果 */
interface PageResult<T> {
  /** 总记录数 */
  total: number
  /** 总页数 */
  pages: number
  /** 当前页码 */
  current: number
  /** 数据列表 */
  records: T[]
}

export type { Result, PageResult }
