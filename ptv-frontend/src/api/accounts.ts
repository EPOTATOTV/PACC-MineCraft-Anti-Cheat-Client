import type {
  Account,
  AdminRole,
  CrossEvidenceChain,
  SystemAdmins,
  SystemConfig,
  SystemInfo,
  TenantAdminRow,
  TenantQuotaView,
  TenantRow,
  TenantUsageView,
} from '../types'
import { request } from './http'

export const accountsApi = {
  accounts: {
    list: (keyword?: string) =>
      request<Account[]>(`/accounts?keyword=${encodeURIComponent(keyword ?? '')}`),
  },

  // ---- P-06 跨端证据链：同一 PTEID 的玩家端 + 服务端事件 ----
  crossEvidence: {
    player: (pteid: string) =>
      request<CrossEvidenceChain>(`/cross-evidence/players/${encodeURIComponent(pteid)}`),
  },

  roles: {
    list: () => request<AdminRole[]>('/roles'),
    get: (id: string) => request<AdminRole>(`/roles/${id}`),
    save: (body: Record<string, unknown>) =>
      request<AdminRole>('/roles', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: Record<string, unknown>) =>
      request<AdminRole>(`/roles/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id: string) => request<{ ok: boolean }>(`/roles/${id}`, { method: 'DELETE' }),
  },

  // ---- v4.8 多租户架构：租户管理 ----
  tenant: {
    list: (params: Record<string, string> = {}) => {
      const p = new URLSearchParams(params)
      return request<{ rows: TenantRow[]; total: number; page?: number }>(`/tenant/list?${p.toString()}`)
    },
    get: (id: string) => request<TenantRow>(`/tenant/${id}`),
    create: (body: Record<string, unknown>) =>
      request<TenantRow>('/tenant', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: Record<string, unknown>) =>
      request<TenantRow>(`/tenant/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id: string) =>
      request<{ ok: boolean }>(`/tenant/${id}`, { method: 'DELETE' }),
    admins: (id: string) => request<TenantAdminRow[]>(`/tenant/${id}/admins`),
    addAdmin: (id: string, identity: string, role: string) =>
      request<TenantAdminRow>(`/tenant/${id}/admins`, { method: 'POST', body: JSON.stringify({ identity, role }) }),
    removeAdmin: (id: string, identity: string) =>
      request<{ ok: boolean }>(`/tenant/${id}/admins/${encodeURIComponent(identity)}`, { method: 'DELETE' }),
    setAdminEnabled: (id: string, identity: string, enabled: boolean) =>
      request<{ ok: boolean }>(`/tenant/${id}/admins/${encodeURIComponent(identity)}/enabled`, {
        method: 'PUT',
        body: JSON.stringify({ enabled }),
      }),

    // ---- §4.2.3 多租户配额 / 计费计量 ----
    quota: () => request<TenantQuotaView>('/tenant/quota'),
    usage: (tenantId?: string) =>
      request<TenantUsageView>(`/tenant/usage${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''}`),
  },

  // ---- v5.0 系统管理 ----
  system: {
    info: () => request<SystemInfo>('/system/info'),
    config: () => request<SystemConfig>('/system/config'),
    admins: () => request<SystemAdmins>('/system/admins'),
  },
}
