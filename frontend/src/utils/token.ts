const TOKEN_KEY = 'sa-token'

/** 从 localStorage 获取 Token */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** 向 localStorage 写入 Token */
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 从 localStorage 清除 Token */
export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}
