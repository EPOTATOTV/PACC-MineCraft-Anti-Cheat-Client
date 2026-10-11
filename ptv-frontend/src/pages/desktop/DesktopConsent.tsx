import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { desktopBridge } from '../../services/desktopBridge'

/**
 * 桌面壳 · 首次启动协议确认视图。
 * <p>Tauri 壳在未接受当前版本协议时隐藏主窗口并创建 `consent` 窗口，
 * 注入 `window.__PACC_VIEW__='consent'`，App 据此渲染本组件（见 App.tsx）。</p>
 * <p>内容为《隐私政策》《服务协议》要点摘要，与下载站 legal 页同源；
 * 必须滚动到底部才能「接受并继续」，确认结果写回壳侧 consent.json。</p>
 */
const SECTIONS: { title: string; body: string[] }[] = [
  {
    title: '一、这份协议管什么',
    body: [
      'PACC 反作弊客户端常驻运行并上报运行环境信号，服务端据此给出风险判定，供赛事方或运营方处置。继续安装即表示你已阅读并同意本协议与《隐私政策》。',
    ],
  },
  {
    title: '二、客户端会上报什么',
    body: [
      '为判定作弊与风险评分，客户端上报：进程与驱动清单、模块加载记录；硬件与系统指纹摘要；运行时行为快照（输入时序统计、内存与调用完整性检查结果）；对抗性环境信号（调试器、DMA 设备、虚拟化/沙箱迹象）。',
      '我们不上报你的个人文件内容、聊天记录或浏览器历史，也不做屏幕录制。数据仅用于反作弊判定、账号安全与运维，不用于广告，也不出售给第三方。',
    ],
  },
  {
    title: '三、使用前提',
    body: [
      '你有权在目标设备上安装并运行本客户端；客户端需常驻，并按平台要求获得系统权限（Windows 需内核驱动，Linux 需内核模块与守护进程），关闭或卸载会导致赛事环境不可用；设备需具备可达的上报链路。',
    ],
  },
  {
    title: '四、你不得',
    body: [
      '逆向、反编译或篡改客户端，绕过、屏蔽其检测与上报；伪造上报数据或硬件指纹，或复用他人账号；对接口刷量、压测或未授权自动化调用；将本客户端用于破解其它安全产品，或任何违法违规用途。',
    ],
  },
  {
    title: '五、下载与校验',
    body: [
      '下载站公布每个发布物的 SHA-256 与文件体积，请在安装前自行核对；校验和不一致的安装包请勿运行，并联系我们。我们不会通过聊天工具单独向你发送安装包。',
    ],
  },
  {
    title: '六、判定与申诉',
    body: [
      '风险判定由服务端综合评分给出，可能包含误判。对结果有异议的，可通过 support@potatotv.asia 提起申诉，并提供账号标识、时间点与相关日志。',
    ],
  },
  {
    title: '七、责任边界',
    body: [
      '本客户端按「现状」提供。因你关闭、卸载、篡改或绕过客户端，系统环境不满足要求，或不可抗力、第三方网络故障造成的损失，我们不承担责任；也不对赛事主办方的最终处置决定作担保。',
    ],
  },
  {
    title: '八、保存多久与你的权利',
    body: [
      '风险判定与证据留痕按赛事与运营需要保留，到期删除；登录审计短期保留；下载计数为长期保留的聚合数据，不含个人标识。你可通过 support@potatotv.asia 请求查询、更正或删除与你账号相关的数据；涉及赛事判定证据的部分需先完成申诉流程。',
    ],
  },
  {
    title: '九、变更与终止',
    body: [
      '出于安全或合规原因，我们可以更新客户端与本协议，重大变更会在下载站与本页说明。若你不同意变更，请停止使用并卸载客户端。',
    ],
  },
]

export default function DesktopConsent() {
  const [version, setVersion] = useState('')
  const [scrolled, setScrolled] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const bodyRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    desktopBridge
      .consentStatus()
      .then((s) => setVersion(s.version))
      .catch(() => {
        /* 壳不可用时不阻塞阅读，接受时再报错 */
      })
  }, [])

  const checkBottom = useCallback(() => {
    const el = bodyRef.current
    if (!el) return
    if (el.scrollHeight - el.scrollTop - el.clientHeight <= 8) setScrolled(true)
  }, [])

  useEffect(() => {
    // 内容不足一屏时无需滚动，直接放行
    const el = bodyRef.current
    if (el && el.scrollHeight <= el.clientHeight + 8) setScrolled(true)
  }, [])

  const onAccept = useCallback(async () => {
    setBusy(true)
    setError('')
    try {
      await desktopBridge.acceptConsent(version || 'unknown')
      // 成功后壳会关闭本窗口并显示主窗口，无需在此更新 UI
    } catch (e) {
      setError(e instanceof Error ? e.message : '接受协议失败，请重试')
      setBusy(false)
    }
  }, [version])

  return (
    <div style={styles.root}>
      <style>{`
        .pacc-consent-body::-webkit-scrollbar { width: 8px; }
        .pacc-consent-body::-webkit-scrollbar-thumb { background: rgba(120,130,150,.45); border-radius: 4px; }
        .pacc-consent-btn {
          padding: 10px 40px;
          font-size: 15px;
          font-weight: 700;
          letter-spacing: 1px;
          color: #ffffff;
          background: #d2433a;
          border: none;
          border-radius: 6px;
          cursor: pointer;
          transition: background .15s ease;
        }
        .pacc-consent-btn:hover:not(:disabled) { background: #b8342c; }
        .pacc-consent-btn:disabled { background: #c9ccd3; cursor: default; }
      `}</style>

      <header style={styles.header}>
        <div style={styles.brand}>PACC 玩家协议</div>
        <div style={styles.brandSub}>版本 {version || '—'} · POTATOTV</div>
      </header>

      <div ref={bodyRef} className="pacc-consent-body" style={styles.body} onScroll={checkBottom}>
        <p style={styles.lead}>
          请完整阅读以下条款。滚动到最底部后即可点击「接受并继续」；不接受请退出并卸载客户端。
        </p>
        {SECTIONS.map((s) => (
          <section key={s.title} style={styles.section}>
            <h2 style={styles.sectionTitle}>{s.title}</h2>
            {s.body.map((p, i) => (
              <p key={i} style={styles.paragraph}>
                {p}
              </p>
            ))}
          </section>
        ))}
        <p style={styles.foot}>—— 以上为本协议全文 ——</p>
      </div>

      <footer style={styles.footer}>
        {error ? <div style={styles.error}>{error}</div> : null}
        <div style={styles.footerHint}>
          {scrolled ? '已阅读完毕，可接受本协议' : '请向下滚动阅读完整条款'}
        </div>
        <button className="pacc-consent-btn" onClick={onAccept} disabled={!scrolled || busy}>
          {busy ? '处理中…' : '接受并继续'}
        </button>
      </footer>
    </div>
  )
}

const styles: Record<string, CSSProperties> = {
  root: {
    position: 'fixed',
    inset: 0,
    display: 'flex',
    flexDirection: 'column',
    background: '#0d1016',
    color: '#e6e8ee',
    fontFamily: "'PingFang SC','Microsoft YaHei',ui-sans-serif,sans-serif",
  },
  header: {
    padding: '18px 28px 14px',
    borderBottom: '1px solid rgba(255,255,255,.08)',
  },
  brand: { fontSize: 20, fontWeight: 700, letterSpacing: 1 },
  brandSub: { marginTop: 4, fontSize: 12, color: '#8b93a3' },
  body: {
    flex: 1,
    overflowY: 'auto',
    padding: '18px 28px 28px',
    lineHeight: 1.75,
  },
  lead: { margin: '0 0 16px', fontSize: 14, color: '#aeb6c4' },
  section: { marginBottom: 18 },
  sectionTitle: { margin: '0 0 6px', fontSize: 15, fontWeight: 700, color: '#ffffff' },
  paragraph: { margin: '0 0 8px', fontSize: 13.5, color: '#c6ccd8' },
  foot: { marginTop: 8, textAlign: 'center', fontSize: 12, color: '#6b7382' },
  footer: {
    padding: '12px 28px 18px',
    borderTop: '1px solid rgba(255,255,255,.08)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 16,
  },
  footerHint: { fontSize: 12.5, color: '#8b93a3' },
  error: { position: 'absolute', bottom: 74, left: 28, fontSize: 12.5, color: '#ff6b5e' },
}