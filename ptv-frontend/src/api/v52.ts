import type {
  V52BehaviorProfile,
  V52HighRiskPlayer,
  V52ModelVersion,
  V52ReplayList,
  V52ReputationAdjustResult,
  V52ReputationDetail,
  V52TrainResult,
  V53DeviceList,
  V53ReputationOverview,
  V53Trend,
} from '../types'
import { request } from './http'

export const v52Api = {
  // ---- v5.3 管理端补齐：v5.2 模型版本 / 行为画像 / 信誉 v2 / 查端回放 ----
  // 均为只读或既有写接口的调用封装，不改动后端契约。
  v52: {
    model: {
      versions: (modelType?: string) =>
        request<{ versions: V52ModelVersion[] }>(
          `/v52/model/versions${modelType ? `?modelType=${encodeURIComponent(modelType)}` : ''}`,
        ),
      active: (modelType: string) =>
        request<V52ModelVersion>(`/v52/model/active?modelType=${encodeURIComponent(modelType)}`),
      /** 灰度放量：percent 走查询参数（后端 @RequestParam，非请求体）。 */
      gray: (id: string, percent: number) =>
        request<V52ModelVersion>(`/v52/model/${encodeURIComponent(id)}/gray?percent=${percent}`, {
          method: 'POST',
        }),
      activate: (id: string) =>
        request<V52ModelVersion>(`/v52/model/${encodeURIComponent(id)}/activate`, { method: 'POST' }),
      rollback: (id: string) =>
        request<V52ModelVersion>(`/v52/model/${encodeURIComponent(id)}/rollback`, { method: 'POST' }),
      /** 手动触发一轮训练（无入参：窗口与门禁由后端常量决定）。 */
      train: () => request<V52TrainResult>('/v52/model/train', { method: 'POST' }),
    },
    profile: {
      highRisk: (limit = 50) =>
        request<{ players: V52HighRiskPlayer[] }>(`/v52/profile/high-risk?limit=${limit}`),
      detail: (pteid: string) =>
        request<V52BehaviorProfile>(`/v52/profile/${encodeURIComponent(pteid)}`),
      reputation: (pteid: string, limit = 20) =>
        request<V52ReputationDetail>(
          `/v52/profile/${encodeURIComponent(pteid)}/reputation?limit=${limit}`,
        ),
      /** 人工调整信誉分：必须填原因，后端全程审计。 */
      adjust: (pteid: string, delta: number, reason: string) =>
        request<V52ReputationAdjustResult>(
          `/v52/profile/${encodeURIComponent(pteid)}/reputation/adjust`,
          { method: 'POST', body: JSON.stringify({ delta, reason }) },
        ),
    },
    replay: {
      list: (pteid?: string, limit = 20) => {
        const p = new URLSearchParams({ limit: String(limit) })
        if (pteid) p.set('pteid', pteid)
        return request<V52ReplayList>(`/v52/replay?${p.toString()}`)
      },
      /** 解密下载地址：同源导航自动携带管理端会话 cookie。 */
      downloadUrl: (id: string) => `/api/admin/v52/replay/${encodeURIComponent(id)}/download`,
    },
  },

  // ---- v5.3 管理端只读聚合：硬件指纹 / 行为趋势 / 信誉全量分布 / 回放逐帧预览 ----
  v53: {
    devices: (pteid?: string, sharedOnly = false) => {
      const p = new URLSearchParams({ sharedOnly: String(sharedOnly) })
      if (pteid) p.set('pteid', pteid)
      return request<V53DeviceList>(`/v53/devices?${p.toString()}`)
    },
    trend: (pteid: string, days = 30) =>
      request<V53Trend>(`/v53/profile/${encodeURIComponent(pteid)}/trend?days=${days}`),
    reputationOverview: () => request<V53ReputationOverview>('/v53/reputation/overview'),
    /** 逐帧预览地址：JPEG 由浏览器直接加载（同源自动带 cookie），水印已在服务端烧好。 */
    replayFrameUrl: (id: string, index: number) =>
      `/api/admin/v53/replay/${encodeURIComponent(id)}/frame?index=${index}`,
  },
}
