import { lazy } from 'react'
import { Route } from 'react-router-dom'

// /df/screen（全屏大屏）脱离 Layout，由 App 单独处理，不在这里。
const DfAlertNoise = lazy(() => import('../pages/df/AlertNoise'))
const DfAutomation = lazy(() => import('../pages/df/Automation'))
const DfPluginRuntime = lazy(() => import('../pages/df/PluginRuntime'))
const DfTenantQuota = lazy(() => import('../pages/df/TenantQuota'))

/** DF 数据域路由：告警降噪、自动化、插件运行时、租户配额。 */
export const dfRoutes = (
  <>
    <Route path="/df/alerts-noise" element={<DfAlertNoise />} />
    <Route path="/df/automation" element={<DfAutomation />} />
    <Route path="/df/plugins" element={<DfPluginRuntime />} />
    <Route path="/df/tenant-quota" element={<DfTenantQuota />} />
  </>
)
