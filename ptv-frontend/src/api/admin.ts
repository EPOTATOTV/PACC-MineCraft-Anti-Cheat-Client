import type { AdminLoginLog } from '../types'
import { request, timedFetch } from './http'

export const adminApi = {
  login(adminApiKey: string) {
    return timedFetch('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ admin_key: adminApiKey }),
    })
  },

  // 管理后台会话：HttpOnly cookie 承载，/me 探测登录态，/logout 清理 cookie。
  // 登录态探测同样走超时守卫，避免后端无响应时加载页永久停留。
  adminSession: {
    me(): Promise<{ ok: boolean; role: string }> {
      return timedFetch('/api/admin/me', { credentials: 'same-origin' }).then((r) => {
        if (!r.ok) { const e = new Error('未登录') as Error & { status?: number }; e.status = r.status; throw e }
        return r.json()
      })
    },
    logout(): Promise<{ ok: boolean }> {
      return timedFetch('/api/admin/logout', { method: 'POST', credentials: 'same-origin' }).then((r) => r.json())
    },
  },

  // ---- 管理后台登录（密钥 / 飞书）与登录日志 ----
  feishu: {
    url: () => timedFetch('/api/admin/feishu/oauth/url'),
    callback: (code: string) =>
      timedFetch('/api/admin/feishu/oauth/callback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ code }),
      }),
  },
  adminLoginLogs: () => request<{ logs: AdminLoginLog[] }>('/login-logs'),
}
