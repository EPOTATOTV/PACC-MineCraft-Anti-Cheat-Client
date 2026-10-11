import { create } from 'zustand'
import { api } from '../api/client'

/**
 * 管理端登录态。
 *
 * 原先 authed/role 是 App.tsx 里的局部 state：深层组件想用 role 只能逐层传 props，
 * 或者各自再调一次 /api/admin/me。收敛到 store 后，App.tsx 只负责触发一次探测，
 * 其余组件（Layout 的分组过滤、受限资料跳转等）直接读。
 */
interface AuthState {
  /** null 表示探测尚未完成——首屏据此显示 loading，而不是误判成未登录 */
  authed: boolean | null
  role: string
  /** 拉取 /api/admin/me 刷新登录态；失败一律按未登录处理 */
  refresh: () => Promise<void>
  clear: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  authed: null,
  role: '',
  refresh: async () => {
    try {
      const d = await api.adminSession.me()
      set({ authed: true, role: d.role ?? '' })
    } catch {
      set({ authed: false, role: '' })
    }
  },
  clear: () => set({ authed: false, role: '' }),
}))
