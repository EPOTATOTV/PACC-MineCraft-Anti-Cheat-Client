import { lazy } from 'react'
import { Route } from 'react-router-dom'

const ModelManager = lazy(() => import('../pages/v52/ModelManager'))
const BehaviorProfile = lazy(() => import('../pages/v52/BehaviorProfile'))
const ReputationManager = lazy(() => import('../pages/v52/ReputationManager'))
const ReplayManager = lazy(() => import('../pages/v52/ReplayManager'))
const DeviceFingerprint = lazy(() => import('../pages/v52/DeviceFingerprint'))

/** v5.2 AI 能力域路由：模型、画像、信誉、回放、设备指纹。 */
export const v52Routes = (
  <>
    <Route path="/v52/model" element={<ModelManager />} />
    <Route path="/v52/profile" element={<BehaviorProfile />} />
    <Route path="/v52/reputation" element={<ReputationManager />} />
    <Route path="/v52/replay" element={<ReplayManager />} />
    <Route path="/v52/devices" element={<DeviceFingerprint />} />
  </>
)
