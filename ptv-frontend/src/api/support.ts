import type {
  AnyRow,
  Appeal,
  AuditOperationPage,
  AuditOverview,
  OpenApiAuditRow,
  OpenApiKeyRow,
  OpsConfigRow,
  OpsCrashRow,
  OpsHealth,
  OpsOverview,
  OpsTelemetryRow,
  SupportDashboard,
  SupportFaqItem,
  SupportReplyResult,
  SupportTicket,
  SupportTicketItem,
} from '../types'
import { request } from './http'

export const supportApi = {
  // ---- v4.7 自动化运维 ----
  ops: {
    health: () => request<OpsHealth>('/ops/health'),
    crashes: (limit = 50) => request<{ crashes: OpsCrashRow[] }>(`/ops/crashes?limit=${limit}`),
    telemetry: (limit = 50) => request<{ telemetry: OpsTelemetryRow[] }>(`/ops/telemetry?limit=${limit}`),
    overview: () => request<OpsOverview>('/ops/overview'),
    config: () => request<{ configs: OpsConfigRow[] }>('/ops/config'),
    saveConfig: (key: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>(`/ops/config/${key}`, { method: 'PUT', body: JSON.stringify(body) }),
  },

  // ---- v4.7 客服工单 ----
  support: {
    tickets: (category?: string, status?: string) =>
      request<SupportTicketItem[]>(`/support/tickets${category ? `?category=${category}` : ''}${status ? `&status=${status}` : ''}`),
    createTicket: (body: Record<string, string>) =>
      request<SupportTicketItem>('/support/tickets', { method: 'POST', body: JSON.stringify(body) }),
    replyTicket: (id: string, reply: string, responder = 'admin') =>
      request<SupportReplyResult>(`/support/tickets/${id}/reply`, { method: 'POST', body: JSON.stringify({ reply, responder }) }),
    resolveTicket: (id: string) =>
      request<{ ok: boolean }>(`/support/tickets/${id}/resolve`, { method: 'POST', body: JSON.stringify({}) }),
    smartHitRate: () => request<{ hit_rate: number }>('/support/smart/hit-rate'),
    dashboard: () => request<SupportDashboard>('/support/dashboard'),
    faqList: () => request<SupportFaqItem[]>('/support/faq'),
    addFaq: (body: Record<string, string>) => request<SupportFaqItem>('/support/faq', { method: 'POST', body: JSON.stringify(body) }),
    deleteFaq: (id: string) => request<{ ok: boolean }>(`/support/faq/${id}`, { method: 'DELETE' }),
  },

  // ---- v4.8 开放 API 平台：API 密钥管理 + 调用审计 ----
  openApi: {
    keys: (tenant = 'platform') => request<OpenApiKeyRow[]>(`/api/keys?tenant=${tenant}`),
    createKey: (body: Record<string, string | number>) =>
      request<{ keyId: string; secret: string }>('/api/keys', { method: 'POST', body: JSON.stringify(body) }),
    rotateKey: (id: string) =>
      request<{ keyId: string; secret: string }>(`/api/keys/${id}/rotate`, { method: 'POST' }),
    toggleKey: (id: string, enabled: boolean) =>
      request<{ enabled: boolean }>(`/api/keys/${id}/toggle`, { method: 'POST', body: JSON.stringify({ enabled }) }),
    deleteKey: (id: string) =>
      request<{ deleted: boolean }>(`/api/keys/${id}`, { method: 'DELETE' }),
    setWebhook: (id: string, url: string, secret: string) =>
      request<{ updated: boolean }>(`/api/keys/${id}/webhook`, {
        method: 'POST',
        body: JSON.stringify({ url, secret }),
      }),
    audit: (keyId?: string, page = 0, size = 20) =>
      request<{ rows: OpenApiAuditRow[]; total: number }>(`/api/audit?page=${page}&size=${size}${keyId ? `&keyId=${encodeURIComponent(keyId)}` : ''}`),
  },

  // ---- v4.8 合规审计强化：管理员操作审计 ----
  audit: {
    operations: (params: Record<string, string> = {}) => {
      const p = new URLSearchParams(params)
      return request<AuditOperationPage>(`/audit/operations?${p.toString()}`)
    },
    trend: (days = 30) => request<{ days: string[]; counts: number[]; total?: number }>(`/audit/trend?days=${days}`),
    overview: (days = 30) => request<AuditOverview>(`/audit/overview?days=${days}`),
  },

  compliance: {
    selfCheck: () => request<AnyRow>('/compliance/selfcheck'),
    sla: () => request<AnyRow>('/compliance/sla'),
    branding: () => request<AnyRow>('/compliance/branding'),
    sbom: () => request<AnyRow>('/compliance/sbom'),
    supportSummary: () => request<{ pending_appeals: number; open_tickets: number; in_progress_tickets: number }>('/support/summary'),
    supportTickets: (status = 'open') => request<SupportTicket[]>(`/support/tickets?status=${status}`),
    supportAppeals: (status = 'pending') => request<Appeal[]>(`/support/appeals?status=${status}`),
    reviewAppeal: (id: string, body: Record<string, string>) =>
      request<{ ok: boolean }>(`/support/appeals/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    transitionTicket: (id: string, body: Record<string, string>) =>
      request<{ ok: boolean }>(`/support/tickets/${id}/transition`, { method: 'POST', body: JSON.stringify(body) }),
  },
}
