import type {
  V54ApmAlertList,
  V54ApmAnomalyList,
  V54ApmCatalogEntry,
  V54ApmMatrix,
  V54ApmOverview,
  V54ApmTrend,
  V54ApmVersionRegression,
  V54AttestationList,
  V54KeyAudit,
  V54KeyAuditRow,
  V54KnownHash,
  V54KnownHashList,
  V54ManagedKey,
  V54ManagedKeyList,
  V54SecurityChain,
  V54SecurityEventList,
  V54SecurityOverview,
} from '../types'
import { request } from './http'

export const v54Api = {
  // ---- v5.4 性能与安全加固：APM 监控 / 安全审计 / 密钥管理 ----
  // 契约已冻结；查询串统一用 URLSearchParams 构造，空值不写入，避免后端误判为显式筛选项。
  v54: {
    apm: {
      overview: (hours = 24, platform?: string, clientVer?: string) => {
        const p = new URLSearchParams({ hours: String(hours) })
        if (platform) p.set('platform', platform)
        if (clientVer) p.set('clientVer', clientVer)
        return request<V54ApmOverview>(`/apm/overview?${p.toString()}`)
      },
      trend: (metric: string, hours = 24, platform?: string, clientVer?: string) => {
        const p = new URLSearchParams({ metric, hours: String(hours) })
        if (platform) p.set('platform', platform)
        if (clientVer) p.set('clientVer', clientVer)
        return request<V54ApmTrend>(`/apm/trend?${p.toString()}`)
      },
      matrix: (metrics: string[], hours = 24) => {
        const p = new URLSearchParams({ metrics: metrics.join(','), hours: String(hours) })
        return request<V54ApmMatrix>(`/apm/platform-matrix?${p.toString()}`)
      },
      versionRegression: (metric: string, platform?: string) => {
        const p = new URLSearchParams({ metric })
        if (platform) p.set('platform', platform)
        return request<V54ApmVersionRegression>(`/apm/version-regression?${p.toString()}`)
      },
      anomalies: (metric?: string, platform?: string, hours = 168) => {
        const p = new URLSearchParams({ hours: String(hours) })
        if (metric) p.set('metric', metric)
        if (platform) p.set('platform', platform)
        return request<V54ApmAnomalyList>(`/apm/anomalies?${p.toString()}`)
      },
      alerts: (status?: string, limit = 50) => {
        const p = new URLSearchParams({ limit: String(limit) })
        if (status) p.set('status', status)
        return request<V54ApmAlertList>(`/apm/alerts?${p.toString()}`)
      },
      ackAlert: (id: string) =>
        request<{ id: string; status: string }>(`/apm/alerts/${encodeURIComponent(id)}/ack`, { method: 'POST' }),
      catalog: () => request<{ items: V54ApmCatalogEntry[] }>('/apm/catalog'),
    },
    security: {
      overview: (hours = 24) => request<V54SecurityOverview>(`/security/overview?hours=${hours}`),
      events: (params: { level?: string; type?: string; pteid?: string; limit?: number } = {}) => {
        const p = new URLSearchParams({ limit: String(params.limit ?? 100) })
        if (params.level) p.set('level', params.level)
        if (params.type) p.set('type', params.type)
        if (params.pteid) p.set('pteid', params.pteid)
        return request<V54SecurityEventList>(`/security/events?${p.toString()}`)
      },
      chainVerify: (limit = 500) => request<V54SecurityChain>(`/security/chain/verify?limit=${limit}`),
      attestation: (pteid?: string, limit = 50) => {
        const p = new URLSearchParams({ limit: String(limit) })
        if (pteid) p.set('pteid', pteid)
        return request<V54AttestationList>(`/security/attestation?${p.toString()}`)
      },
      hashes: () => request<V54KnownHashList>('/security/hashes'),
      addHash: (body: { label: string; kind: string; hash: string; active: boolean }) =>
        request<V54KnownHash>('/security/hashes', { method: 'POST', body: JSON.stringify(body) }),
      deactivateHash: (id: string) =>
        request<{ ok: boolean }>(`/security/hashes/${encodeURIComponent(id)}/deactivate`, { method: 'POST' }),
    },
    keys: {
      list: () => request<V54ManagedKeyList>('/keys'),
      purposes: () => request<{ items: string[] }>('/keys/purposes'),
      audit: () => request<V54KeyAudit>('/keys/audit'),
      create: (purpose: string, note: string) =>
        request<V54ManagedKey>('/keys', { method: 'POST', body: JSON.stringify({ purpose, note }) }),
      rotate: (keyId: string, note: string) =>
        request<V54ManagedKey>(`/keys/${encodeURIComponent(keyId)}/rotate`, {
          method: 'POST', body: JSON.stringify({ note }),
        }),
      revoke: (keyId: string, reason: string) =>
        request<V54ManagedKey>(`/keys/${encodeURIComponent(keyId)}/revoke`, {
          method: 'POST', body: JSON.stringify({ reason }),
        }),
      keyAudit: (keyId: string) =>
        request<{ items: V54KeyAuditRow[] }>(`/keys/${encodeURIComponent(keyId)}/audit`),
    },
  },
}
