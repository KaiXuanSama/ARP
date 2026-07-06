/**
 * 认证工具 — Token 持久化 + 统一 fetch 封装
 * <p>
 * Token 存 localStorage('agentreproxy.token')，页面刷新后保留。
 * 提供 authFetch 包装原生 fetch，自动注入 Authorization 头，
 * 遇到 401 自动清 token + 跳转登录页。
 */

const TOKEN_KEY = 'agentreproxy.token'

/** 获取当前 token */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** 存储 token */
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

/** 清除 token */
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 是否已有 token（不校验有效性，仅看本地有没有） */
export function hasToken(): boolean {
  const t = getToken()
  return t != null && t.length > 0
}

/**
 * 带 Token 的 fetch 封装
 * <p>
 * - 自动注入 Authorization: Bearer <token>
 * - 401 响应自动清 token + 跳转登录页
 * - 其他行为与原生 fetch 一致
 */
export async function authFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const token = getToken()
  const headers = new Headers(init?.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(input, { ...init, headers })

  if (response.status === 401) {
    clearToken()
    // 跳转登录页（hash 路由模式）
    if (!window.location.hash.includes('/login')) {
      window.location.hash = '#/login'
    }
  }

  return response
}
