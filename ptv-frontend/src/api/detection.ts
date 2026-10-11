import type {
  AbExperimentRow,
  AbSignificance,
  AnyRow,
  CheatRecord,
  CounterMeasureEnvOverview,
  DetectionAnalysis,
  Signature,
  SignatureDiff,
  SignatureSeed,
  ThreatClusterResult,
  ThreatIngestResult,
  V46Overview,
  ZeroDayAssessment,
} from '../types'
import { request } from './http'

export const detectionApi = {
  records: {
    list: (keyword = '') =>
      request<CheatRecord[]>(`/records?keyword=${encodeURIComponent(keyword)}`),
    revoke: (id: string, revoked: boolean) =>
      request<{ record_id: string; revoked: boolean }>(`/records/${id}/revoke`, {
        method: 'POST',
        body: JSON.stringify({ revoked, operator: 'admin' }),
      }),
  },

  signatures: {
    list: (edition: string, state?: string) =>
      request<Signature[]>(`/signatures?edition=${edition}${state ? `&state=${state}` : ''}`),
    add: (data: Record<string, string>) =>
      request<Signature>('/signatures', { method: 'POST', body: JSON.stringify(data) }),
    grayRelease: (edition: string, percent: number) =>
      request<{ ok: boolean }>('/signatures/gray-release', {
        method: 'POST',
        body: JSON.stringify({ edition, percent, operator: 'admin' }),
      }),
    rollback: (edition: string) =>
      request<{ ok: boolean; rolled_back: number }>('/signatures/rollback', {
        method: 'POST',
        body: JSON.stringify({ edition, operator: 'admin' }),
      }),
    diff: (edition: string, afterVersion = 0) =>
      request<SignatureDiff>(`/signatures/diff?edition=${edition}&afterVersion=${afterVersion}&signed=true`),
    autoRollback: (falsePositiveRate: number) =>
      request<{ ok: boolean; rolled_back: number }>('/signatures/auto-rollback', {
        method: 'POST',
        body: JSON.stringify({ false_positive_rate: falsePositiveRate, edition: 'BEDROCK', operator: 'admin' }),
      }),
  },

  // ---- v4.1 ----
  detection41: {
    analyze: (features: Record<string, number>) =>
      request<DetectionAnalysis>('/v41/analyze', { method: 'POST', body: JSON.stringify(features) }),
    demoCheat: () => request<DetectionAnalysis>('/v41/demo/cheat'),
    demoHuman: () => request<DetectionAnalysis>('/v41/demo/human'),
  },

  // ---- v4.5 二期 DMA/IOMMU 环境巡检 ----
  countermeasure: {
    environment: () => request<CounterMeasureEnvOverview>('/countermeasure/environment'),
  },

  // ---- v4.6 检测能力深化：零日 / 威胁情报 / 特征库扩充 / 主动学习 ----
  v46: {
    overview: () => request<V46Overview>('/v46/overview'),
    assessZeroDay: (body: Record<string, unknown>) =>
      request<ZeroDayAssessment>('/v46/zero-day/assess', { method: 'POST', body: JSON.stringify(body) }),
    reviewZeroDay: (id: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>(`/v46/zero-day/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    reflowZeroDay: (id: string, reviewer?: string) =>
      request<{ sample_id?: string; family?: string }>(`/v46/zero-day/${id}/reflow`, { method: 'POST', body: JSON.stringify({ reviewer: reviewer ?? 'admin' }) }),
    ingestThreat: (body: Record<string, unknown>) =>
      request<ThreatIngestResult>('/v46/threat/ingest', { method: 'POST', body: JSON.stringify(body) }),
    reviewThreat: (id: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>(`/v46/threat/${id}/review`, { method: 'POST', body: JSON.stringify(body) }),
    analyzeThreat: (id: string) =>
      request<{ ok: boolean }>(`/v46/threat/analyze/${id}`, { method: 'POST' }),
    clusterThreat: (k?: number) =>
      request<ThreatClusterResult>('/v46/threat/cluster', { method: 'POST', body: JSON.stringify({ k: k ?? 3 }) }),
    threatClusters: () => request<ThreatClusterResult>('/v46/threat/clusters'),
    promoteThreat: (id: string) =>
      request<{ name: string; state: string }>(`/v46/threat/${id}/promote`, { method: 'POST', body: JSON.stringify({ operator: 'admin' }) }),
    signatures: () => request<SignatureSeed[]>('/v46/signatures'),
  },

  // ---- v4.7 威胁情报运营中台：家族谱系 / 主动威慑 / IOC 中心化 ----
  v47: {
    familyOverview: () => request<AnyRow>('/v47/family/overview'),
    familyGraph: () => request<AnyRow>('/v47/family/graph'),
    deterOverview: () => request<AnyRow>('/v47/deter/overview'),
    setDeter: (body: Record<string, unknown>) =>
      request<{ ok: boolean }>('/v47/deter/set', { method: 'POST', body: JSON.stringify(body) }),
    toggleDeter: (id: number, enabled: boolean) =>
      request<{ ok: boolean }>(`/v47/deter/${id}/toggle`, { method: 'POST', body: JSON.stringify({ enabled }) }),
    resolveDeter: (family: string) =>
      request<{ family: string; action: string; severity: number; protected: boolean }>('/v47/deter/resolve', { method: 'POST', body: JSON.stringify({ family }) }),
    iocOverview: () =>
      request<{ total: number; open: number; disarmed: number; subscribed: number; high_severity: number }>('/v47/ioc/overview'),
    iocList: (params: Record<string, string | number> = {}) => {
      const p = new URLSearchParams(params as Record<string, string>)
      return request<{ items: AnyRow[]; total: number }>(`/v47/ioc/list?${p.toString()}`)
    },
    importIoc: (sampleId: string) =>
      request<{ imported: number }>('/v47/ioc/import', { method: 'POST', body: JSON.stringify({ sampleId }) }),
    subscribeIoc: (id: number) => request<{ ok: boolean }>(`/v47/ioc/${id}/subscribe`, { method: 'POST' }),
    disarmIoc: (id: number) => request<{ ok: boolean }>(`/v47/ioc/${id}/disarm`, { method: 'POST' }),
    hitIoc: (id: number) => request<{ ok: boolean }>(`/v47/ioc/${id}/hit`, { method: 'POST' }),
  },

  // ---- v4.7 检测算法 A/B 测试 ----
  ab: {
    list: () => request<AbExperimentRow[]>('/ab'),
    create: (body: Record<string, string | number>) =>
      request<AbExperimentRow>('/ab', { method: 'POST', body: JSON.stringify(body) }),
    significance: (id: string) => request<AbSignificance>(`/ab/${id}/significance`),
    breakdown: (id: string, body: Record<string, unknown>) =>
      request<Record<string, number>>(`/ab/${id}/breakdown`, { method: 'POST', body: JSON.stringify(body) }),
    finish: (id: string) => request<AbExperimentRow>(`/ab/finish/${id}`, { method: 'POST' }),
    publish: (id: string) => request<AbExperimentRow>(`/ab/publish/${id}`, { method: 'POST' }),
  },
}
