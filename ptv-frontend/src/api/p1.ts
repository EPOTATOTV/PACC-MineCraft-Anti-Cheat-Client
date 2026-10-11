import type {
  CheatTypeDistRow,
  DetectorConfigRow,
  DlRelease,
  DlStats,
  EffectConfigAuditRow,
  EffectConfigDto,
  EffectivenessSummary,
  ExportSubmitResult,
  ExportTaskRow,
  ListEntryRow,
  ReleaseInfoRow,
} from '../types'
import { request } from './http'

export const p1Api = {
  // ---- P1（v5.0）：版本发布 / 黑白名单 / 数据导出 / 反作弊效果 / 检测配置 ----
  p1: {
    releases: () =>
      request<{ releases: ReleaseInfoRow[] }>('/releases'),
    createRelease: (body: Record<string, string | number>) =>
      request<string>('/releases', { method: 'POST', body: JSON.stringify(body) }),
    updateRelease: (id: string, body: Record<string, unknown>) =>
      request<string>(`/releases/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    publishRelease: (id: string) =>
      request<string>(`/releases/${id}/publish`, { method: 'POST', body: JSON.stringify({}) }),
    archiveRelease: (id: string) =>
      request<{ ok: boolean }>(`/releases/${id}/archive`, { method: 'POST' }),
    deleteRelease: (id: string) =>
      request<{ ok: boolean }>(`/releases/${id}`, { method: 'DELETE' }),

    lists: (params: Record<string, string> = {}) => {
      const p = new URLSearchParams(params)
      return request<{ list_types: string[]; entry_types: string[]; entries: ListEntryRow[] }>(`/lists?${p.toString()}`)
    },
    addListEntry: (body: Record<string, unknown>) =>
      request<string>('/lists', { method: 'POST', body: JSON.stringify(body) }),
    removeListEntry: (id: string) =>
      request<{ removed: boolean }>(`/lists/${id}`, { method: 'DELETE' }),

    exports: () => request<{ tasks: ExportTaskRow[] }>('/export'),
    submitExport: (body: Record<string, string>) =>
      request<ExportSubmitResult>('/export', { method: 'POST', body: JSON.stringify(body) }),
    exportDownloadUrl: (taskId: string, key: string) => `/api/admin/export/${taskId}/download?key=${encodeURIComponent(key)}`,

    effectiveness: () => request<EffectivenessSummary>('/effectiveness'),
    cheatTypeDistribution: () =>
      request<{ cheat_types: CheatTypeDistRow[] }>('/effectiveness/cheat-types'),

    detectorConfig: () =>
      request<{ detectors: DetectorConfigRow[]; catalog: DetectorConfigRow[] }>('/detector-config'),
    saveDetectorConfig: (body: Record<string, unknown>) =>
      request<DetectorConfigRow>('/detector-config', { method: 'PUT', body: JSON.stringify(body) }),
  },

  // ---- v5.0 动效配置中心与下载站 ----
  effect: {
    get: () => request<EffectConfigDto>('/effect'),
    update: (body: Record<string, unknown>) =>
      request<EffectConfigDto>('/effect', { method: 'PUT', body: JSON.stringify(body) }),
    history: (limit = 20) => request<EffectConfigAuditRow[]>(`/effect/history?limit=${limit}`),
  },
  dl: {
    releases: () => request<DlRelease[]>('/dl/releases'),
    stats: (days = 14) => request<DlStats>(`/dl/stats?days=${days}`),
  },
}
