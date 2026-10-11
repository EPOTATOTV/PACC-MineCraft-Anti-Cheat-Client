import { lazy } from 'react'
import { Route } from 'react-router-dom'

const ApmMonitor = lazy(() => import('../pages/v54/ApmMonitor'))
const SecurityAudit = lazy(() => import('../pages/v54/SecurityAudit'))
const KeyManagement = lazy(() => import('../pages/v54/KeyManagement'))

/** v5.4 安全域路由：APM 监控、安全审计、密钥管理。 */
export const v54Routes = (
  <>
    <Route path="/v54/apm" element={<ApmMonitor />} />
    <Route path="/v54/security" element={<SecurityAudit />} />
    <Route path="/v54/keys" element={<KeyManagement />} />
  </>
)
