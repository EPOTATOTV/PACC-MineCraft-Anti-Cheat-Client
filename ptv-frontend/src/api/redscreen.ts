import type { Account, IceServersResponse, InspectSession, RedscreenAlert } from '../types'
import { request } from './http'

export const redscreenApi = {
  redscreens: {
    list: (state = 'PENDING_INSPECT') =>
      request<RedscreenAlert[]>(`/redscreens?state=${state}`),
  },

  inspects: {
    pending: () => request<InspectSession[]>('/inspects/pending'),
    all: () => request<InspectSession[]>('/inspects/all'),
    start: (sessionId: string) =>
      request<InspectSession>(`/inspects/${sessionId}/start`, { method: 'POST',
        body: JSON.stringify({ operator: 'admin' }) }),
    conclude: (sessionId: string, conclusion: string, note: string) =>
      request<Account>(`/inspects/${sessionId}/conclude`, {
        method: 'POST',
        body: JSON.stringify({ conclusion, note }),
      }),
  },

  // ---- 远程查端 WebRTC 接入参数（STUN/TURN 由服务端下发，前端不写死） ----
  webrtc: {
    iceServers: () => request<IceServersResponse>('/webrtc/ice-servers'),
  },
}
