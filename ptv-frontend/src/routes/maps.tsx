import { lazy } from 'react'
import { Route } from 'react-router-dom'

const MapPoolManager = lazy(() => import('../pages/maps/MapPoolManager'))
const BpSessions = lazy(() => import('../pages/maps/BpSessions'))
const BpConsole = lazy(() => import('../pages/maps/BpConsole'))

/** 地图池与 BP 流程域路由。 */
export const mapsRoutes = (
  <>
    <Route path="/maps" element={<MapPoolManager />} />
    <Route path="/maps/bp" element={<BpSessions />} />
    <Route path="/maps/bp/:bpId" element={<BpConsole />} />
  </>
)
