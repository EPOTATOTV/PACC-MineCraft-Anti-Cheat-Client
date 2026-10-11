import type {
  AutomationExecutionPage,
  AutomationExecutionRow,
  AutomationRuleRow,
  DfStreamMetrics,
  PluginMarketList,
  PluginRuntimeRow,
} from '../types'
import { request } from './http'

export const dfApi = {
  // ---- §4.3.3 自动化响应：规则 / 启停 / 执行审计 ----
  automation: {
    rules: () => request<AutomationRuleRow[]>('/automation/rules'),
    toggleRule: (code: string, enabled: boolean) =>
      request<AutomationRuleRow>(`/automation/rules/${encodeURIComponent(code)}/toggle`, {
        method: 'POST',
        body: JSON.stringify({ enabled }),
      }),
    executions: (page = 0, size = 20) =>
      request<AutomationExecutionPage>(`/automation/executions?page=${page}&size=${size}`),
    revertExecution: (id: number) =>
      request<AutomationExecutionRow>(`/automation/executions/${id}/revert`, { method: 'POST' }),
  },

  // ---- §4.2.2 插件运行时：市场条目 + 运行时热加载 / 卸载 ----
  plugins: {
    market: (status?: string, page = 0, size = 20) => {
      const p = new URLSearchParams({ page: String(page), size: String(size) })
      if (status) p.set('status', status)
      return request<PluginMarketList>(`/plugins?${p.toString()}`)
    },
    runtime: () => request<PluginRuntimeRow[]>('/plugins/runtime'),
    loadRuntime: (id: string, path?: string) =>
      request<PluginRuntimeRow>(`/plugins/runtime/${encodeURIComponent(id)}/load`, {
        method: 'POST',
        body: JSON.stringify(path ? { path } : {}),
      }),
    unloadRuntime: (id: string) =>
      request<PluginRuntimeRow>(`/plugins/runtime/${encodeURIComponent(id)}/unload`, { method: 'POST' }),
  },

  // ---- DF §4.1.1 流式检测运行指标（延迟百分位，A18 观测面） ----
  df: {
    streamMetrics: () => request<DfStreamMetrics>('/df/stream/metrics'),
  },
}
