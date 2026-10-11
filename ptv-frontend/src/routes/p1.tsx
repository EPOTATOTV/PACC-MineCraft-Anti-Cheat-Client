import { lazy } from 'react'
import { Route } from 'react-router-dom'

const Releases = lazy(() => import('../pages/p1/Releases'))
const RuleEditor = lazy(() => import('../pages/p1/RuleEditor'))
const Lists = lazy(() => import('../pages/p1/Lists'))
const Exports = lazy(() => import('../pages/p1/Exports'))
const Effectiveness = lazy(() => import('../pages/p1/Effectiveness'))
const DetectorConfig = lazy(() => import('../pages/p1/DetectorConfig'))
const EffectConfig = lazy(() => import('../pages/p1/EffectConfig'))

/** P1 检测运营域路由：发布、PRL 规则、名单、导出、效果、配置、动效。 */
export const p1Routes = (
  <>
    <Route path="/releases" element={<Releases />} />
    <Route path="/prl-editor" element={<RuleEditor />} />
    <Route path="/lists" element={<Lists />} />
    <Route path="/export" element={<Exports />} />
    <Route path="/effectiveness" element={<Effectiveness />} />
    <Route path="/config" element={<DetectorConfig />} />
    <Route path="/motion" element={<EffectConfig />} />
  </>
)
