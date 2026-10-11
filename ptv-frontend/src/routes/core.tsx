import { lazy } from 'react'
import { Route } from 'react-router-dom'

// 核心监控 / 检测引擎 / 账号权限 / 赛事 / 文档协议 / 系统治理。
// 页面一律按需加载：全站 80+ 页面若静态引入会打进同一个 chunk。
const Dashboard = lazy(() => import('../pages/Dashboard'))
const Redscreen = lazy(() => import('../pages/Redscreen'))
const Inspect = lazy(() => import('../pages/Inspect'))
const SignatureLibrary = lazy(() => import('../pages/SignatureLibrary'))
const Accounts = lazy(() => import('../pages/Accounts'))
const CheatRecords = lazy(() => import('../pages/CheatRecords'))
const Competition = lazy(() => import('../pages/Competition'))
const Tournament = lazy(() => import('../pages/Tournament'))
const StreamLive = lazy(() => import('../pages/StreamLive'))
const Detection41 = lazy(() => import('../pages/Detection41'))
const Countermeasure = lazy(() => import('../pages/Countermeasure'))
const V46Detection = lazy(() => import('../pages/V46Detection'))
const V47ThreatIntel = lazy(() => import('../pages/V47ThreatIntel'))
const Compliance = lazy(() => import('../pages/Compliance'))
const AdminLoginLogs = lazy(() => import('../pages/AdminLoginLogs'))
const Audit = lazy(() => import('../pages/Audit'))
const Tenant = lazy(() => import('../pages/Tenant'))
const Admins = lazy(() => import('../pages/Admins'))
const System = lazy(() => import('../pages/System'))
const BiReport = lazy(() => import('../pages/bi/BiReport'))
const OpenApi = lazy(() => import('../pages/openapi/OpenApi'))
const AbExperiment = lazy(() => import('../pages/AbExperiment'))
const OpsCenter = lazy(() => import('../pages/OpsCenter'))
const SupportCenter = lazy(() => import('../pages/SupportCenter'))

/** 核心域路由：仪表盘、红屏、查端、检测引擎、账号、赛事、文档协议、系统治理。 */
export const coreRoutes = (
  <>
    <Route path="/" element={<Dashboard />} />
    <Route path="/redscreen" element={<Redscreen />} />
    <Route path="/inspect" element={<Inspect />} />
    <Route path="/signatures" element={<SignatureLibrary />} />
    <Route path="/accounts" element={<Accounts />} />
    <Route path="/records" element={<CheatRecords />} />
    <Route path="/competition" element={<Competition />} />
    <Route path="/tournament" element={<Tournament />} />
    <Route path="/stream-live" element={<StreamLive />} />
    <Route path="/detection41" element={<Detection41 />} />
    <Route path="/countermeasure" element={<Countermeasure />} />
    <Route path="/v46" element={<V46Detection />} />
    <Route path="/v47" element={<V47ThreatIntel />} />
    <Route path="/compliance" element={<Compliance />} />
    <Route path="/login-logs" element={<AdminLoginLogs />} />
    <Route path="/audit" element={<Audit />} />
    <Route path="/tenant" element={<Tenant />} />
    <Route path="/admins" element={<Admins />} />
    <Route path="/system" element={<System />} />
    <Route path="/bi" element={<BiReport />} />
    <Route path="/openapi" element={<OpenApi />} />
    <Route path="/ab" element={<AbExperiment />} />
    <Route path="/ops" element={<OpsCenter />} />
    <Route path="/support" element={<SupportCenter />} />
  </>
)
