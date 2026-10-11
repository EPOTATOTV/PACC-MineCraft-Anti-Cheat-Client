import type {
  Appeal,
  AppealDetail,
  AiModelStatus,
  BpStateDto,
  Broadcast,
  CheatRecord,
  DetectionEvent,
  DetectionLogLine,
  DetectorStatus,
  DeviceRecord,
  Enrollment,
  LoginDevice,
  MapBanPickAction,
  MapBanPickSession,
  MapEntry,
  MapPool,
  MatchValidateResult,
  Peripheral,
  PlayerCurrentMatch,
  PlayerEnrollmentStatus,
  PlayerNotification,
  PlayerRegisterInfo,
  PlayerSummary,
  ProtectionResource,
  ProtectionStat,
  ProtectionStatus,
  RedScreenDetail,
  ReputationSummary,
  SecurityScore,
  SupportTicket,
  TicketMessage,
  TournamentNotice,
  TournamentStage,
} from '../types'
import { playerRequest, timedFetch } from './http'

export const playerApi = {
  // ---- 玩家账号 / 门户：所有认证 / 注册 / 找回流程统一加超时守卫 ----
  auth: {
    login(identity: string, password: string, remember = false) {
      return timedFetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ identity, password, remember }),
      })
    },
    login2fa(pending: string, code: string, remember = false) {
      return timedFetch('/api/auth/login/2fa', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pending, code, remember }),
      })
    },
    register(body: Record<string, string>) {
      return timedFetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
    forget(email: string) {
      return timedFetch('/api/auth/forget', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      })
    },
    reset(token: string, new_password: string) {
      return timedFetch('/api/auth/reset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, new_password }),
      })
    },
    sendCode(body: Record<string, string>) {
      return timedFetch('/api/auth/code/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
    verifyCode(body: Record<string, string>) {
      return timedFetch('/api/auth/code/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    },
  },
  player: {
    me: () => playerRequest<{ pteid: string }>('/me'),
    logout: () => fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }),
    summary: () => playerRequest<PlayerSummary>('/summary'),
    records: () => playerRequest<CheatRecord[]>('/records'),
    appeals: () => playerRequest<Appeal[]>('/appeals'),
    submitAppeal: (body: Record<string, string>) =>
      playerRequest<Appeal>('/appeals', { method: 'POST', body: JSON.stringify(body) }),
    tickets: () => playerRequest<SupportTicket[]>('/tickets'),
    submitTicket: (body: Record<string, string>) =>
      playerRequest<SupportTicket>('/tickets', { method: 'POST', body: JSON.stringify(body) }),
    devices: () => playerRequest<DeviceRecord[]>('/devices'),
    peripherals: () => playerRequest<Peripheral[]>('/peripherals'),
    myEnrollment: () => playerRequest<PlayerEnrollmentStatus>('/competition/enrollment'),
    myCurrentMatch: () => playerRequest<PlayerCurrentMatch>('/competition/matches/current'),
    validateMatch: (match_token: string) =>
      playerRequest<MatchValidateResult>('/competition/matches/validate', {
        method: 'POST',
        body: JSON.stringify({ match_token }),
      }),
    stages: () => playerRequest<TournamentStage[]>('/competition/stages'),
    notices: () => playerRequest<TournamentNotice[]>('/competition/notices'),
    registerInfo: (tournamentId: string) =>
      playerRequest<PlayerRegisterInfo>(`/competition/register?tournament_id=${encodeURIComponent(tournamentId)}`),
    submitRegister: (body: Record<string, string>) =>
      playerRequest<Enrollment>('/competition/register', { method: 'POST', body: JSON.stringify(body) }),
    liveStreams: () => playerRequest<Broadcast[]>('/stream-live'),

    // ---- PACC 4.0 玩家端 P0：实时保护 / 监控 / 通知 / 安全 ----
    protection: {
      status: () => playerRequest<ProtectionStatus>('/protection/status'),
      resources: () => playerRequest<ProtectionResource[]>('/protection/resources'),
      stats: () => playerRequest<ProtectionStat>('/protection/stats'),
      recentEvents: (limit = 10) =>
        playerRequest<DetectionEvent[]>(`/protection/events?limit=${limit}`),
      pause: () => playerRequest<{ running: boolean }>('/protection/pause', { method: 'POST' }),
      resume: () => playerRequest<{ running: boolean }>('/protection/resume', { method: 'POST' }),
      scanNow: () => playerRequest<{ ok: boolean }>('/protection/scan', { method: 'POST' }),
    },
    monitor: {
      detectors: () => playerRequest<DetectorStatus[]>('/monitor/detectors'),
      ai: () => playerRequest<AiModelStatus>('/monitor/ai'),
      logs: (params: Record<string, string | number> = {}) => {
        const p = new URLSearchParams(params as Record<string, string>)
        return playerRequest<DetectionLogLine[]>(`/monitor/logs?${p.toString()}`)
      },
    },
    redscreenDetail: (id: string) => playerRequest<RedScreenDetail>(`/redscreen/${id}`),
    notifications: {
      list: (kind?: string) =>
        playerRequest<PlayerNotification[]>(`/notifications${kind ? `?kind=${kind}` : ''}`),
      read: (id: string) =>
        playerRequest<{ ok: boolean }>(`/notifications/${id}/read`, { method: 'POST' }),
      readAll: () => playerRequest<{ ok: boolean }>('/notifications/read-all', { method: 'POST' }),
      remove: (id: string) =>
        playerRequest<{ ok: boolean }>(`/notifications/${id}`, { method: 'DELETE' }),
      unreadCount: () => playerRequest<{ count: number }>('/notifications/unread-count'),
    },
    security: {
      score: () => playerRequest<SecurityScore>('/security/score'),
      devices: () => playerRequest<LoginDevice[]>('/security/devices'),
      logoutDevice: (deviceId: string) =>
        playerRequest<{ ok: boolean }>(`/security/devices/${deviceId}/logout`, { method: 'POST' }),
      changePassword: (body: Record<string, string>) =>
        playerRequest<{ ok: boolean }>('/security/password', { method: 'POST', body: JSON.stringify(body) }),
      totpSetup: () => playerRequest<{ secret: string; otpauth: string }>('/security/totp/setup'),
      totpEnable: (body: Record<string, string>) =>
        playerRequest<{ ok: boolean }>('/security/totp/enable', { method: 'POST', body: JSON.stringify(body) }),
      totpStatus: () =>
        playerRequest<{ enabled: boolean; recovery_ready: boolean }>('/security/totp/status'),
      totpRecovery: () =>
        playerRequest<{ recovery_codes: string[] }>('/security/totp/recovery', { method: 'POST' }),
      totpDisable: (code: string) =>
        playerRequest<{ ok: boolean }>('/security/totp/disable', {
          method: 'POST',
          body: JSON.stringify({ code }),
        }),
    },
    // ---- 地图 BP（Ban/Pick）：玩家端浏览与参与 ----
    maps: {
      pools: () => playerRequest<MapPool[]>('/maps/pools'),
      pool: (poolId: string) => playerRequest<MapPool>(`/maps/pools/${encodeURIComponent(poolId)}`),
      entries: (poolId: string) =>
        playerRequest<MapEntry[]>(`/maps/pools/${encodeURIComponent(poolId)}/entries`),
      bpCurrent: () => playerRequest<MapBanPickSession[]>('/maps/bp/current'),
      bpHistory: () => playerRequest<MapBanPickSession[]>('/maps/bp/history'),
      bpState: (bpId: string) => playerRequest<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}`),
      bpActions: (bpId: string) =>
        playerRequest<MapBanPickAction[]>(`/maps/bp/${encodeURIComponent(bpId)}/actions`),
      bpAction: (bpId: string, body: { action: string; map_id?: string; device_fingerprint?: string }) =>
        playerRequest<BpStateDto>(`/maps/bp/${encodeURIComponent(bpId)}/action`, {
          method: 'POST',
          body: JSON.stringify(body),
        }),
    },
    appealDetail: (id: string) => playerRequest<AppealDetail>(`/appeals/${id}`),
    appealMessages: (id: string) => playerRequest<AppealDetail>(`/appeals/${id}`),
    ticketMessages: (id: string) => playerRequest<TicketMessage[]>(`/tickets/${id}/messages`),
    ticketReply: (id: string, reply: string) =>
      playerRequest<TicketMessage>(`/tickets/${id}/reply`, {
        method: 'POST',
        body: JSON.stringify({ reply }),
      }),
  },

  // ---- 玩家门户：信誉分（P1） ----
  reputation: {
    summary: () => playerRequest<ReputationSummary>('/reputation'),
  },
}
