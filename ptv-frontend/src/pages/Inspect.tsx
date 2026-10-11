import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Badge, Button, Card, Drawer, List, Modal, Radio, Space, Table, Tag, Typography, message } from 'antd'
import { SyncOutlined } from '@ant-design/icons'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { InspectSession } from '../types'
import { StatusPill } from '../components/StatusPill'
import { acceptScreenShare, type SignalTransport } from '../webrtc/webrtc'
import { useWebSocket } from '../ws/useWebSocket'

const { Title, Text } = Typography

/** 查端结论（与后端强制取值一致）。 */
const CONCLUSION_LABEL = {
  CONFIRMED_CHEAT: '确认作弊',
  FALSE_POSITIVE: '误报',
  PENDING: '待分析',
} as const
type Conclusion = keyof typeof CONCLUSION_LABEL

interface Forensics {
  session_id?: string
  os?: string
  java?: string
  cpu_threads?: number
  processes?: { pid: number; name?: string; cpu?: string }[]
}

export default function Inspect() {
  const [pending, setPending] = useState<InspectSession[]>([])
  const [all, setAll] = useState<InspectSession[]>([])
  const [err, setErr] = useState('')
  const [note, setNote] = useState('')
  const [conclusionChoice, setConclusionChoice] = useState<Conclusion | ''>('')
  const [modal, setModal] = useState<InspectSession | null>(null)
  const [view, setView] = useState<InspectSession | null>(null)
  const [wsStatus, setWsStatus] = useState<'连接中' | '已连接' | '已断开' | '掉线'>('连接中')
  const [log, setLog] = useState<string[]>([])
  const [forensics, setForensics] = useState<Forensics | null>(null)
  const [turnConfigured, setTurnConfigured] = useState<boolean | null>(null)
  const [iceError, setIceError] = useState('')
  const [nowMs, setNowMs] = useState(() => Date.now())
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const screenRef = useRef<{ onSignal: (msg: Record<string, unknown>) => Promise<void>; stop: () => void } | null>(null)
  const [screenOn, setScreenOn] = useState(false)

  const load = useCallback(async () => {
    try {
      const [p, a] = await Promise.all([api.inspects.pending(), api.inspects.all()])
      setPending(p)
      setAll(a)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => { load() }, [load])

  // 查端抽屉打开时建立 /ws/admin 信令通道（统一事件总线），关闭时退订
  const wsUrl = useMemo(() => {
    if (!view) return ''
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    return `${proto}://${window.location.host}/ws/admin?session_id=${encodeURIComponent(view.sessionId)}`
  }, [view])

  const { status: connStatus, send } = useWebSocket(wsUrl, {
    onMessage: (record) => {
      setLog((l) => [...l, JSON.stringify(record)].slice(-30))
      if (record?.type === 'inspect_started') {
        setLog((l) => [...l, '玩家已连接，等待取证...'])
      } else if (record?.type === 'inspect_forensics') {
        setForensics(record as Forensics)
      } else if (record?.type === 'inspect_offer' || record?.type === 'inspect_ice') {
        setScreenOn(true)
        screenRef.current?.onSignal(record).catch(console.error)
      }
    },
  })

  // 打开抽屉：重置状态，拉取后端下发的 ICE（STUN/TURN）后创建 WebRTC 被叫
  useEffect(() => {
    if (!view || !videoRef.current) return
    let cancelled = false
    const video = videoRef.current
    let screen: { onSignal: (msg: Record<string, unknown>) => Promise<void>; stop: () => void } | null = null
    setLog([]); setForensics(null); setScreenOn(false); setWsStatus('连接中')
    setTurnConfigured(null); setIceError(''); setNowMs(Date.now())
    void (async () => {
      let rtcConfig: RTCConfiguration | undefined
      try {
        const r = await api.webrtc.iceServers()
        setTurnConfigured(r.turn_configured)
        if (r.ice_servers.length > 0) rtcConfig = { iceServers: r.ice_servers }
      } catch (e) {
        setIceError((e as Error).message)
      }
      if (cancelled) return
      const transport: SignalTransport = { send }
      screen = acceptScreenShare(transport, view.sessionId, video, rtcConfig)
      screenRef.current = screen
    })()
    return () => {
      cancelled = true
      screen?.stop()
      screenRef.current = null
      setScreenOn(false)
    }
  }, [view, send])

  // WS 连接状态 → 页面展示状态
  useEffect(() => {
    if (connStatus === 'connected') setWsStatus('已连接')
    else if (connStatus === 'connecting' || connStatus === 'reconnecting') setWsStatus('连接中')
    else if (connStatus === 'disconnected') setWsStatus((s) => (s === '已连接' ? '掉线' : '已断开'))
  }, [connStatus])

  // 查看中的会话每 30s 刷新一次时钟，用于 30 分钟超时提示
  useEffect(() => {
    if (!view) return
    const id = setInterval(() => setNowMs(Date.now()), 30_000)
    return () => clearInterval(id)
  }, [view])

  const sessionExpired = useMemo(() => {
    if (!view?.expiresAt) return false
    return nowMs >= new Date(view.expiresAt).getTime()
  }, [view, nowMs])
  const linkLost = wsStatus === '掉线' || wsStatus === '已断开'

  async function start(session: InspectSession) {
    try {
      await api.inspects.start(session.sessionId)
      message.success('已开始查端会话')
      setView(session)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  function openConclude(session: InspectSession) {
    setModal(session)
    setConclusionChoice('')
    setNote('')
  }

  async function doConclude() {
    if (!modal) return
    if (!conclusionChoice) {
      message.warning('请先选择查端结论')
      return
    }
    try {
      await api.inspects.conclude(modal.sessionId, conclusionChoice, note)
      message.success(`已提交结论：${CONCLUSION_LABEL[conclusionChoice]}`)
      setModal(null); setNote(''); setConclusionChoice(''); setView(null)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  const pendingCols: TableColumnsType<InspectSession> = [
    { title: '会话 ID', dataIndex: 'sessionId' },
    { title: 'PTEID', dataIndex: 'pteid' },
    { title: '关联警告', dataIndex: 'alertId' },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: InspectSession['state']) => <StatusPill value={v} /> },
    { title: '过期时间', dataIndex: 'expiresAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    {
      title: '操作', dataIndex: 'sessionId', width: 120,
      render: (_, s) => <Button size="small" onClick={() => start(s)}>开始查端</Button>,
    },
  ]

  const allCols: TableColumnsType<InspectSession> = [
    { title: 'PTEID', dataIndex: 'pteid' },
    { title: '操作员', dataIndex: 'operator', render: (v?: string) => v || '-' },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: InspectSession['state']) => <StatusPill value={v} /> },
    { title: '结论', dataIndex: 'conclusion', render: (v?: string) => (v && v in CONCLUSION_LABEL ? CONCLUSION_LABEL[v as Conclusion] : v || '-') },
    { title: '开始时间', dataIndex: 'startedAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    {
      title: '操作', dataIndex: 'state', width: 200,
      render: (_, s) =>
        s.state === 'ACTIVE' ? (
          <Space size={6}>
            <Button size="small" onClick={() => setView(s)}>实时取证</Button>
            <Button size="small" type="primary" onClick={() => openConclude(s)}>给出结论</Button>
          </Space>
        ) : null,
    },
  ]

  const wsColor = wsStatus === '已连接' ? 'success' : wsStatus === '连接中' ? 'processing' : 'error'

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>查端控制台</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>待查端实时取证 · 屏幕共享 · 结论判定</Text>
        <div style={{ flex: 1 }} />
        <Button icon={<SyncOutlined />} onClick={load}>刷新</Button>
        {err && <Alert type="error" showIcon title={err} style={{ flexBasis: '100%' }} closable={{ onClose: () => setErr('') }} />}
      </div>

      <Card title="待处理队列" style={{ marginBottom: 16 }} styles={{ body: { padding: 0 } }}>
        <Table<InspectSession> rowKey="sessionId" columns={pendingCols} dataSource={pending} pagination={false} scroll={{ x: 640 }} locale={{ emptyText: '队列为空' }} />
      </Card>

      <Card title="全部会话" styles={{ body: { padding: 0 } }}>
        <Table<InspectSession> rowKey="sessionId" columns={allCols} dataSource={all} pagination={{ pageSize: 15, hideOnSinglePage: true }} scroll={{ x: 760 }} locale={{ emptyText: '无会话' }} />
      </Card>

      <Modal
        title={`提交查端结论 · ${modal?.sessionId ?? ''}`}
        open={!!modal}
        onOk={doConclude}
        okButtonProps={{ disabled: !conclusionChoice }}
        okText="提交并结束查端"
        onCancel={() => { setModal(null); setNote(''); setConclusionChoice('') }}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          title="必须给出结论才能结束查端。红屏解锁只能由带结论的结束动作触发；选「待分析」会结束会话但红屏保持锁定。"
        />
        <p style={{ color: '#8b949e' }}>会话 {modal?.sessionId} · PTEID {modal?.pteid}</p>
        <Radio.Group value={conclusionChoice} onChange={(e) => setConclusionChoice(e.target.value as Conclusion)}>
          <Space orientation="vertical">
            <Radio value="CONFIRMED_CHEAT">确认作弊（红屏转 CONFIRMED，永久记录保留）</Radio>
            <Radio value="FALSE_POSITIVE">误报（红屏转 FALSE_POSITIVE，撤销记录并恢复信誉）</Radio>
            <Radio value="PENDING">待分析（结束会话，红屏保持锁定）</Radio>
          </Space>
        </Radio.Group>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="备注（可选）"
          rows={3}
          style={{ width: '100%', marginTop: 12, background: 'var(--panel)', color: 'var(--text)', border: '1px solid var(--border-strong)', borderRadius: 6, padding: 8 }}
        />
      </Modal>

      <Drawer
        title={`查端实时信令 · ${view?.sessionId ?? ''}`}
        width={520}
        open={!!view}
        onClose={() => setView(null)}
      >
        <div style={{ marginBottom: 12 }}>
          <Badge status={wsColor} text={wsStatus} />
          <Text type="secondary" style={{ marginLeft: 12 }}>PTEID {view?.pteid}</Text>
        </div>

        {sessionExpired && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            title="会话已超过 30 分钟，连接将自动断开。红屏不会自动解除，需管理员给出结论后才能解锁。"
          />
        )}
        {linkLost && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 12 }}
            title="信令连接已断开，屏幕共享中断。请重试；红屏不会自动解除，需管理员给出结论。"
          />
        )}
        {iceError && (
          <Alert type="error" showIcon style={{ marginBottom: 12 }} title={`获取 ICE 服务器失败：${iceError}`} />
        )}
        {turnConfigured === false && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            title="后端未配置 TURN 中继，仅使用 STUN。企业 NAT / 防火墙环境下可能无法建立屏幕共享连接。"
          />
        )}

        <Card size="small" title="实时屏幕" style={{ marginBottom: 12 }}>
          <video
            ref={videoRef}
            muted
            playsInline
            autoPlay
            style={{
              width: '100%',
              aspectRatio: '16 / 9',
              background: '#010409',
              borderRadius: 6,
              display: screenOn ? 'block' : 'none',
            }}
          />
          {!screenOn && (
            <Text type="secondary" style={{ lineHeight: '128px', display: 'block', textAlign: 'center' }}>
              等待玩家端开启屏幕共享...
            </Text>
          )}
        </Card>

        {forensics ? (
          <Card size="small" title="玩家端取证" style={{ marginBottom: 12 }}>
            <p style={{ margin: '4px 0' }}><Text strong>OS：</Text>{forensics.os ?? '-'}</p>
            <p style={{ margin: '4px 0' }}><Text strong>Java：</Text>{forensics.java ?? '-'}</p>
            <p style={{ margin: '4px 0' }}><Text strong>逻辑线程：</Text>{forensics.cpu_threads ?? '-'}</p>
            <List
              size="small"
              bordered
              header={<Text strong>进程快照（按 CPU 排序）</Text>}
              dataSource={forensics.processes ?? []}
              renderItem={(p) => (
                <List.Item style={{ padding: '6px 10px' }}>
                  <span style={{ color: '#e6edf3', fontFamily: 'monospace' }}>{p.pid}</span>
                  <span style={{ flex: 1, margin: '0 8px', color: '#c9d1d9' }}>{p.name ?? '?'}</span>
                  <Tag style={{ margin: 0 }}>{p.cpu ?? '-'}</Tag>
                </List.Item>
              )}
            />
          </Card>
        ) : (
          <Text type="secondary">等待玩家端回传取证...</Text>
        )}

        <Card size="small" title="信令回放" styles={{ body: { padding: 0 } }}>
          <div style={{ maxHeight: 240, overflow: 'auto', padding: '8px 12px', fontFamily: 'monospace', fontSize: 12 }}>
            {log.length === 0 ? (
              <Text type="secondary">暂无信令</Text>
            ) : (
              log.map((l, i) => (
                <div key={i} style={{ color: '#8b949e', padding: '2px 0', wordBreak: 'break-all' }}>{l}</div>
              ))
            )}
          </div>
        </Card>

        {view && (
          <Button type="primary" block style={{ marginTop: 12 }} onClick={() => openConclude(view)}>
            结束查端并提交结论
          </Button>
        )}
      </Drawer>
    </div>
  )
}