import type {
  AdminPlayerDetail,
  AlertEvent,
  AlertNoiseGroupPage,
  AlertNoiseStats,
  AlertRule,
  AlertStats,
  AlertSuppressionRule,
  AlertsListItem,
  BiCheatTypeRow,
  BiLoginAudit,
  BiOverview,
  BiPlayerProfile,
  BiRealtime,
  BiRedscreenHealth,
  BiTrend,
  CheatTypeCount,
  RealtimeAlert,
  RealtimeOverview,
  RedScreenDetail,
  RuntimeStat,
  StatsSummary,
  TrendPoint,
} from '../types'
import { request } from './http'

export const biApi = {
  stats: {
    summary: () => request<StatsSummary>('/stats/summary'),
    cheatTypes: () => request<CheatTypeCount[]>('/stats/cheat-types'),
    trend: (days = 7) => request<TrendPoint[]>(`/stats/redscreen-trend?days=${days}`),
  },

  // ---- v4.8 数据平台与 BI 报表 ----
  bi: {
    overview: (days = 30, startDate?: string, endDate?: string) => {
      const p = new URLSearchParams({ days: String(days) })
      if (startDate) p.set('startDate', startDate)
      if (endDate) p.set('endDate', endDate)
      return request<BiOverview>(`/bi/overview?${p.toString()}`)
    },
    detectionTrend: (days = 30) => request<BiTrend>(`/bi/detection-trend?days=${days}`),
    redscreenTrend: (days = 30) => request<BiTrend>(`/bi/redscreen-trend?days=${days}`),
    cheatTypes: () => request<{ items: BiCheatTypeRow[] }>('/bi/cheat-types'),
    redscreenHealth: () => request<BiRedscreenHealth>('/bi/redscreen-health'),
    playerProfile: () => request<BiPlayerProfile>('/bi/player-profile'),
    loginAudit: (days = 30) => request<BiLoginAudit>(`/bi/login-audit?days=${days}`),
    /** 实时大屏快照：单次请求拿到在线数、近 1h/24h 检测与红屏量、待查验与最新红屏事件流。 */
    realtime: () => request<BiRealtime>('/bi/realtime'),
  },

  // ---- PACC 4.0 管理端 P0：实时监控 / 玩家详情 / 告警中心 / 角色权限 ----
  realtime: {
    overview: () => request<RealtimeOverview>('/realtime/overview'),
    runtime: () => request<RuntimeStat[]>('/realtime/runtime'),
    events: (limit = 30) => request<RealtimeAlert[]>(`/realtime/events?limit=${limit}`),
    alerts: (limit = 20) => request<RealtimeAlert[]>(`/realtime/alerts?limit=${limit}`),
  },
  playerDetail: (pteid: string) => request<AdminPlayerDetail>(`/players/${encodeURIComponent(pteid)}`),
  redscreenAdminDetail: (id: string) => request<RedScreenDetail>(`/redscreens/${id}`),
  alerts: {
    list: (level?: string, status?: string) => {
      const p = new URLSearchParams()
      if (level) p.set('level', level)
      if (status) p.set('status', status)
      return request<AlertsListItem[]>(`/alerts?${p.toString()}`)
    },
    rules: () => request<AlertRule[]>('/alerts/rules'),
    saveRule: (body: Record<string, unknown>) =>
      request<AlertRule>('/alerts/rules', { method: 'POST', body: JSON.stringify(body) }),
    toggleRule: (id: string, enabled: boolean) =>
      request<{ ok: boolean }>(`/alerts/rules/${id}`, { method: 'PUT', body: JSON.stringify({ enabled }) }),
    ack: (id: string) => request<{ ok: boolean }>(`/alerts/${id}/ack`, { method: 'POST' }),
    events: (status?: string, limit = 50) => {
      const p = new URLSearchParams()
      if (status) p.set('status', status)
      p.set('limit', String(limit))
      return request<AlertEvent[]>(`/alerts/events?${p.toString()}`)
    },
    ackEvent: (id: string) => request<AlertEvent>(`/alerts/events/${id}/ack`, { method: 'POST' }),
    resolveEvent: (id: string, note?: string) =>
      request<AlertEvent>(`/alerts/events/${id}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ note: note ?? '' }),
      }),
    stats: () => request<AlertStats>('/alerts/stats'),

    // ---- §4.3.2 智能告警降噪：降噪率统计 / 聚合组 / 误报抑制规则 ----
    noiseStats: (windowHours = 24) =>
      request<AlertNoiseStats>(`/alerts/noise/stats?windowHours=${windowHours}`),
    noiseGroups: (page = 0, size = 20, windowHours = 24) =>
      request<AlertNoiseGroupPage>(`/alerts/groups?page=${page}&size=${size}&windowHours=${windowHours}`),
    suppressions: () => request<AlertSuppressionRule[]>('/alerts/suppressions'),
    addSuppression: (body: {
      name: string
      pattern: string
      familyCode?: string
      reason?: string
    }) => request<AlertSuppressionRule>('/alerts/suppressions', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
    removeSuppression: (id: string) =>
      request<{ deleted: string }>(`/alerts/suppressions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  },
}
