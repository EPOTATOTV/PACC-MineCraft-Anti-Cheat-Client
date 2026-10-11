import { Suspense, lazy, useEffect } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { Spin } from 'antd'
import Layout from './components/Layout'
import PageTransition from './components/PageTransition'
import TopProgressBar from './components/TopProgressBar'
import AppErrorBoundary from './components/AppErrorBoundary'
import { useAuthStore } from './store/auth'
import { coreRoutes } from './routes/core'
import { mapsRoutes } from './routes/maps'
import { p1Routes } from './routes/p1'
import { v52Routes } from './routes/v52'
import { v54Routes } from './routes/v54'
import { dfRoutes } from './routes/df'

// 页面组件一律按需加载：全站 80+ 页面若静态引入会打进同一个 chunk，
// 首屏要把大屏、报表、图表这些多数人用不到的实现一起下载。
// 管理端其余路由按域拆到 src/routes/*（F-12），这里只留脱离 Layout 的特殊视图。
const Login = lazy(() => import('./pages/Login'))
const DfScreen = lazy(() => import('./pages/df/DfScreen'))
const PlayerPortal = lazy(() => import('./pages/player/PlayerPortal'))
const PlayerScreenShare = lazy(() => import('./pages/player/PlayerScreenShare'))
const DesktopRedscreen = lazy(() => import('./pages/desktop/DesktopRedscreen'))
const DesktopConsent = lazy(() => import('./pages/desktop/DesktopConsent'))

/** 按需加载页面时的占位，避免切换路由时白屏 */
function PageLoading() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '40vh' }}>
      <Spin />
    </div>
  )
}

// 「文档与协议」分组（含内部技术资料）仅超级管理员可访问；非超管直接访问时重定向回数据大盘。
// 前端重定向只是 UX 优化，真正的越权拦截在服务端（@SuperAdminOnly）。
const GATED_DOCS = ['/detection41', '/countermeasure', '/v46', '/v47', '/openapi', '/bi']

export default function App() {
  // 先声明 Hook（必须无条件、固定顺序），再做路径分支渲染，避免条件调用 Hook
  const { pathname } = useLocation()
  const authed = useAuthStore((s) => s.authed)
  const role = useAuthStore((s) => s.role)
  const refresh = useAuthStore((s) => s.refresh)

  useEffect(() => {
    void refresh()
  }, [refresh])

  const docGated = role !== 'super-admin' && GATED_DOCS.some((p) => pathname === p || pathname.startsWith(p + '/'))

  function renderRoute() {
    // 桌面壳（Tauri）为红屏/协议确认窗口注入视图标记：这些窗口直接渲染专用视图，
    // 不进入管理端鉴权与路由（窗口以静态资源根路径加载，没有可用的 pathname）。
    const desktopView = (window as unknown as { __PACC_VIEW__?: string }).__PACC_VIEW__
    if (desktopView === 'redscreen') {
      return <DesktopRedscreen />
    }
    if (desktopView === 'consent') {
      return <DesktopConsent />
    }

    // 玩家自助门户独立于管理端鉴权，路径以 /portal 开头即进入
    if (pathname.startsWith('/portal')) {
      return <PlayerPortal />
    }

    // 远程查端屏幕共享页：桌面壳 WebView 以 /screen-share 打开，独立于管理端鉴权
    if (pathname.startsWith('/screen-share')) {
      return <PlayerScreenShare />
    }

    if (authed === null) {
      return (
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}>
          <Spin />
        </div>
      )
    }

    if (!authed) {
      return (
        <Routes>
          <Route path="*" element={<Login />} />
        </Routes>
      )
    }

    // DF §4.3.1 数据可视化大屏：全屏墙显，脱离 Layout（无侧栏/面包屑），仅保留登录态校验
    if (pathname.startsWith('/df/screen')) {
      return <DfScreen />
    }

    if (docGated) {
      return <Navigate to="/" replace />
    }

    return (
      <Layout>
        <TopProgressBar />
        <PageTransition>
          <Routes>
            {coreRoutes}
            {mapsRoutes}
            {p1Routes}
            {v52Routes}
            {v54Routes}
            {dfRoutes}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </PageTransition>
      </Layout>
    )
  }

  // 错误边界放在 Suspense 外层：懒加载分块拉取失败也能落到降级页，而不是整站白屏。
  return (
    <AppErrorBoundary>
      <Suspense fallback={<PageLoading />}>{renderRoute()}</Suspense>
    </AppErrorBoundary>
  )
}
