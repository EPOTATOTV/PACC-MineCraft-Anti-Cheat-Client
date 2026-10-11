// 管理后台凭据已迁至 HttpOnly 会话 cookie（pacc_admin）：JS 不再持有/读取密钥或令牌，
// 由浏览器同源自动附带；登录态统一由后端 /api/admin/me 探测判定。

// 玩家门户凭据已迁至 HttpOnly 会话 cookie：JS 不再持有/读取令牌，仅依赖浏览器自动携带。

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  const res = await fetch(`/api/admin${path}`, { ...init, headers, credentials: 'same-origin' })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  return res.json() as Promise<T>
}

// 玩家门户请求：凭据由同源 HttpOnly cookie 自动携带；会话失效时抛 401，由页面跳回登录
export async function playerRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  const res = await fetch(`/api/player${path}`, { ...init, headers, credentials: 'same-origin' })
  if (res.status === 401) {
    throw new Error('登录已过期，请重新登录')
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `请求失败 (${res.status})`)
  }
  return res.json() as Promise<T>
}

/** 登录等关键交互请求的超时守卫：防止请求挂起时按钮永久停留在 loading 状态。 */
export const LOGIN_TIMEOUT_MS = 15_000

export async function timedFetch(input: RequestInfo | URL, init: RequestInit = {}, ms = LOGIN_TIMEOUT_MS): Promise<Response> {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), ms)
  try {
    return await fetch(input, { ...init, signal: ctl.signal })
  } finally {
    clearTimeout(timer)
  }
}
