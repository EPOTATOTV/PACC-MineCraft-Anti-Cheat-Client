import type {
  BpStateDto,
  Broadcast,
  CompetitionOverview,
  Enrollment,
  EnrollmentStats,
  IpCluster,
  MapBanPickAction,
  MapBanPickSession,
  MapEntry,
  MapPool,
  MapPoolStats,
  MatchSession,
  SuspicionFlag,
  TournamentConfig,
  TournamentNotice,
  TournamentStage,
} from '../types'
import { request } from './http'

export const competitionApi = {
  competition: {
    overview: () => request<CompetitionOverview>('/competition/overview'),
    ipClusters: (minAccounts = 3) =>
      request<IpCluster[]>(`/competition/ip-clusters?min_accounts=${minAccounts}`),
    flags: (keyword = '') =>
      request<SuspicionFlag[]>(`/competition/flags?keyword=${encodeURIComponent(keyword)}`),
    review: (flagId: string, decision: string, comment = '') =>
      request<{ ok: boolean }>(`/competition/flags/${flagId}/review`, {
        method: 'POST',
        body: JSON.stringify({ decision, reviewer: 'admin', comment }),
      }),
    enrollments: () => request<Enrollment[]>('/competition/enrollments'),
    enrollmentStats: () => request<EnrollmentStats>('/competition/enrollments/stats'),
    enroll: (body: Record<string, string>) =>
      request<Enrollment>('/competition/enrollments', { method: 'POST', body: JSON.stringify(body) }),
    approve: (id: string, approve: boolean, note = '') =>
      request<{ ok: boolean }>(`/competition/enrollments/${id}/approve`, {
        method: 'POST',
        body: JSON.stringify({ approve, operator: 'admin', note }),
      }),
    setTeam: (id: string, body: Record<string, string>) =>
      request<Enrollment>(`/competition/enrollments/${id}/team`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    setTeamBatch: (body: { enrollment_ids: string[]; team_name: string; team_color: string }) =>
      request<{ updated: number }>(`/competition/enrollments/team/batch`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    matches: () => request<MatchSession[]>('/competition/matches'),
    startMatch: (body: Record<string, string>) =>
      request<MatchSession>('/competition/matches', { method: 'POST', body: JSON.stringify(body) }),
    endMatch: (matchId: string) =>
      request<{ ok: boolean }>(`/competition/matches/${matchId}/end`, { method: 'POST' }),
    stages: (tournamentId: string) =>
      request<TournamentStage[]>(`/competition/stages?tournament_id=${encodeURIComponent(tournamentId)}`),
    addStage: (body: Record<string, string>) =>
      request<TournamentStage>('/competition/stages', { method: 'POST', body: JSON.stringify(body) }),
    updateStage: (stageId: string, body: Record<string, unknown>) =>
      request<{ ok: boolean }>(`/competition/stages/${stageId}`, { method: 'PUT', body: JSON.stringify(body) }),
    reorderStage: (tournamentId: string, from: number, to: number) =>
      request<{ ok: boolean }>('/competition/stages/reorder', {
        method: 'PATCH',
        body: JSON.stringify({ tournament_id: tournamentId, from, to }),
      }),
    deleteStage: (stageId: string) =>
      request<{ ok: boolean }>(`/competition/stages/${stageId}`, { method: 'DELETE' }),
    notices: (tournamentId: string) =>
      request<TournamentNotice[]>(`/competition/notices?tournament_id=${encodeURIComponent(tournamentId)}`),
    publishNotice: (body: Record<string, string | boolean>) =>
      request<TournamentNotice>('/competition/notices', { method: 'POST', body: JSON.stringify(body) }),
    deleteNotice: (noticeId: string) =>
      request<{ ok: boolean }>(`/competition/notices/${noticeId}`, { method: 'DELETE' }),
    config: (tournamentId: string) =>
      request<TournamentConfig>(`/competition/config?tournament_id=${encodeURIComponent(tournamentId)}`),
    updateConfig: (body: Record<string, string>) =>
      request<TournamentConfig>('/competition/config', { method: 'PUT', body: JSON.stringify(body) }),
  },

  // ---- v5.0 赛事直播转播：管理端 CRUD ----
  streamLive: {
    list: () => request<{ rows: Broadcast[]; total: number }>('/stream-live'),
    create: (body: Record<string, unknown>) =>
      request<Broadcast>('/stream-live', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: Record<string, unknown>) =>
      request<Broadcast>(`/stream-live/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id: string) =>
      request<{ ok: boolean }>(`/stream-live/${id}`, { method: 'DELETE' }),
    setLive: (id: string, live: boolean) =>
      request<Broadcast>(`/stream-live/${id}/live`, { method: 'PUT', body: JSON.stringify({ live }) }),
  },

  // ---- 地图 BP（Ban/Pick）：管理端地图池 / 地图条目 / BP 会话 ----
  maps: {
    pools: () => request<MapPool[]>('/maps/pools'),
    createPool: (body: Record<string, string>) =>
      request<MapPool>('/maps/pools', { method: 'POST', body: JSON.stringify(body) }),
    updatePool: (poolId: string, body: Record<string, string>) =>
      request<MapPool>(`/maps/pools/${encodeURIComponent(poolId)}`, { method: 'PUT', body: JSON.stringify(body) }),
    deletePool: (poolId: string) =>
      request<{ pool_id: string; ok: boolean }>(`/maps/pools/${encodeURIComponent(poolId)}`, { method: 'DELETE' }),

    entries: (poolId: string) =>
      request<MapEntry[]>(`/maps/pools/${encodeURIComponent(poolId)}/entries`),
    poolStats: (poolId: string) =>
      request<MapPoolStats>(`/maps/pools/${encodeURIComponent(poolId)}/stats`),
    createEntry: (poolId: string, body: Record<string, string>) =>
      request<MapEntry>(`/maps/pools/${encodeURIComponent(poolId)}/entries`, { method: 'POST', body: JSON.stringify(body) }),
    batchAddEntries: (poolId: string, body: { rows: Record<string, unknown>[]; created_by?: string }) =>
      request<{ pool_id: string; added: number }>(`/maps/pools/${encodeURIComponent(poolId)}/entries/batch`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    updateEntry: (mapId: string, body: Record<string, string | number | boolean>) =>
      request<MapEntry>(`/maps/entries/${encodeURIComponent(mapId)}`, { method: 'PUT', body: JSON.stringify(body) }),
    deleteEntry: (mapId: string) =>
      request<{ map_id: string; ok: boolean }>(`/maps/entries/${encodeURIComponent(mapId)}`, { method: 'DELETE' }),
    toggleEntry: (mapId: string) =>
      request<{ map_id: string; ok: boolean }>(`/maps/entries/${encodeURIComponent(mapId)}/toggle`, { method: 'POST' }),

    bpSessions: () => request<MapBanPickSession[]>('/maps/bp'),
    createBp: (body: Record<string, string>) =>
      request<MapBanPickSession>('/maps/bp', { method: 'POST', body: JSON.stringify(body) }),
    bpState: (bpId: string) => request<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}`),
    bpActions: (bpId: string) =>
      request<MapBanPickAction[]>(`/maps/bp/${encodeURIComponent(bpId)}/actions`),
    bpStart: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/start`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpPause: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/pause`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpResume: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/resume`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpReset: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/reset`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpCancel: (bpId: string, operator = 'admin', reason = '裁判取消') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/cancel`, {
        method: 'POST',
        body: JSON.stringify({ operator, reason }),
      }),
    bpComplete: (bpId: string, operator = 'admin') =>
      request<MapBanPickSession>(`/maps/bp/${encodeURIComponent(bpId)}/complete`, {
        method: 'POST',
        body: JSON.stringify({ operator }),
      }),
    bpForceAction: (bpId: string, body: { action: string; map_id?: string; operator?: string }) =>
      request<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}/force`, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  },
}
