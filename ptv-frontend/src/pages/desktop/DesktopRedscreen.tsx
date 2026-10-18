import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import { desktopBridge } from '../../services/desktopBridge'

/**
 * 桌面壳 · 红屏全屏警告视图。
 * <p>由 Tauri 壳为每个显示器创建的 `redscreen-*` 窗口渲染：壳注入
 * `window.__PACC_VIEW__='redscreen'`，App 据此渲染本组件（见 App.tsx）。</p>
 * <p>被检测者：键盘已暂停，只等待管理员远程查端，不提供「继续」；
 * 其它在线玩家：收到的是公示红屏，保留键盘，提供「继续」关闭本机窗口。</p>
 */
interface ViewState {
  level: number
  isTarget: boolean
  pteid: string
}

const INITIAL: ViewState = { level: 2, isTarget: true, pteid: '' }

/** 脱敏算法与服务端 RedscreenService.mask 保持一致。 */
function maskPteid(pteid: string): string {
  if (!pteid || pteid.length < 4) return '****'
  return pteid.slice(0, 2) + '***' + pteid.slice(-2)
}

export default function DesktopRedscreen() {
  const [state, setState] = useState<ViewState>(INITIAL)
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const st = await desktopBridge.status()
      setState({
        level: st.redscreen_level ?? 2,
        // 判定失败时保守视为被检测者，避免误给「继续」按钮。
        isTarget: st.redscreen_is_target ?? true,
        pteid: st.pteid ?? '',
      })
    } catch {
      // 壳/本地控制服务瞬时不可用：保留默认（严肃提示），等事件或下一次刷新
    }
  }, [])

  useEffect(() => {
    void refresh()
    const offStatus = desktopBridge.onEvent('status_update', () => void refresh())
    const offTrigger = desktopBridge.onEvent('redscreen_triggered', () => void refresh())
    return () => {
      offStatus()
      offTrigger()
    }
  }, [refresh])

  const onContinue = useCallback(async () => {
    setBusy(true)
    try {
      await desktopBridge.dismissRedscreen()
    } catch {
      // 关闭失败时按钮可再点，不阻塞用户
    } finally {
      setBusy(false)
    }
  }, [])

  const headline = state.isTarget ? '检测到违规行为' : '反作弊公示'

  return (
    <div style={styles.root}>
      <style>{`
        .pacc-rs-btn {
          margin-top: 28px;
          padding: 12px 56px;
          font-size: 20px;
          font-weight: 700;
          letter-spacing: 2px;
          color: #b00000;
          background: #ffffff;
          border: none;
          border-radius: 6px;
          cursor: pointer;
          box-shadow: 0 6px 22px rgba(0,0,0,.35);
          transition: transform .12s ease, box-shadow .12s ease;
        }
        .pacc-rs-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 10px 28px rgba(0,0,0,.42); }
        .pacc-rs-btn:active:not(:disabled) { transform: translateY(0); }
        .pacc-rs-btn:disabled { opacity: .6; cursor: default; }
      `}</style>

      <div style={styles.mark}>PACC 警告</div>
      <h1 style={styles.headline}>【{headline}】</h1>
      <div style={styles.meta}>账号：{maskPteid(state.pteid)}</div>

      {state.isTarget ? (
        <div style={styles.tip}>
          <div style={styles.tipMain}>请保持现状，等待管理员远程查端。</div>
          <div style={styles.tipSub}>输入已暂停，请勿关闭本窗口。</div>
        </div>
      ) : (
        <div style={styles.tip}>
          <div style={styles.tipMain}>本机未被判定为作弊，以下是对当前告警的公示。</div>
          <button className="pacc-rs-btn" onClick={onContinue} disabled={busy}>
            继续
          </button>
        </div>
      )}
    </div>
  )
}

const styles: Record<string, CSSProperties> = {
  root: {
    position: 'fixed',
    inset: 0,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    textAlign: 'center',
    padding: '6vw',
    boxSizing: 'border-box',
    color: '#ffffff',
    fontFamily: "'SimHei','PingFang SC','Microsoft YaHei',sans-serif",
    background: 'radial-gradient(120% 120% at 50% 30%, #ff2d2d 0%, #d40000 45%, #8f0000 100%)',
    zIndex: 2147483647,
    userSelect: 'none',
  },
  mark: {
    fontSize: 'clamp(36px, 6vw, 64px)',
    fontWeight: 800,
    letterSpacing: '4px',
    textShadow: '0 4px 18px rgba(0,0,0,.35)',
  },
  headline: {
    margin: '18px 0 10px',
    fontSize: 'clamp(26px, 4vw, 40px)',
    fontWeight: 700,
  },
  meta: {
    fontSize: 'clamp(14px, 1.6vw, 20px)',
    opacity: 0.92,
    marginTop: 4,
  },
  tip: {
    marginTop: 34,
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
  },
  tipMain: {
    fontSize: 'clamp(16px, 1.8vw, 22px)',
  },
  tipSub: {
    marginTop: 8,
    fontSize: 'clamp(13px, 1.4vw, 17px)',
    opacity: 0.85,
  },
}