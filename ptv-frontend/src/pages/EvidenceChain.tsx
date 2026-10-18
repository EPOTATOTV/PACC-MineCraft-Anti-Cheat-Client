import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Card, Col, Empty, Input, List, Row, Space, Table, Tag, Typography } from 'antd'
import { LinkOutlined, SearchOutlined } from '@ant-design/icons'
import type { TableColumnsType } from 'antd'
import PageHeader from '../components/PageHeader'
import MetricCard from '../components/MetricCard'
import { api } from '../api/client'
import type { CrossEvidenceChain, CrossEvidenceDevice, CrossEvidenceItem } from '../types'

const { Text } = Typography

const SOURCE_META: Record<CrossEvidenceItem['source'], { label: string; color: string }> = {
  CLIENT: { label: '玩家端', color: 'blue' },
  SERVER: { label: '服务端', color: 'purple' },
}

const SEVERITY_COLOR: Record<string, string> = {
  critical: 'error',
  high: 'volcano',
  medium: 'warning',
  low: 'default',
}

/**
 * P-06 跨端证据链：同一个 PTEID 下合并玩家端检测事件与服务端插件事件。
 * 只读查询，不改动任何检测结论；服务端未安装玩家端的事件没有 PTEID，不会出现在这里。
 */
export default function EvidenceChain() {
  const [params, setParams] = useSearchParams()
  const initial = params.get('pteid') ?? ''
  const [keyword, setKeyword] = useState(initial)
  const [pteid, setPteid] = useState(initial)
  const [chain, setChain] = useState<CrossEvidenceChain | null>(null)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)

  const load = useCallback(async (value: string) => {
    const key = value.trim()
    if (!key) {
      setChain(null)
      setErr('')
      return
    }
    setLoading(true)
    setErr('')
    try {
      setChain(await api.crossEvidence.player(key))
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    setPteid(initial)
    setKeyword(initial)
    void load(initial)
  }, [initial, load])

  const submit = useCallback(() => {
    const key = keyword.trim()
    setPteid(key)
    setParams(key ? { pteid: key } : {})
    void load(key)
  }, [keyword, load, setParams])

  return (
    <div>
      <PageHeader
        title="跨端证据链"
        description="按 PTEID 合并玩家端与服务端检测事件"
        error={err}
        onCloseError={() => setErr('')}
      />

      <Card size="small" style={{ marginBottom: 14 }}>
        <Space.Compact style={{ width: '100%', maxWidth: 520 }}>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="输入 PTEID，例如 PT1234ABCD"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={submit}
          />
          <Button type="primary" onClick={submit}>查询</Button>
        </Space.Compact>
      </Card>

      {chain && (
        <Row gutter={[14, 14]} style={{ marginBottom: 14 }}>
          <Col xs={12} sm={6}>
            <MetricCard label="证据总数" value={chain.items.length} accent="var(--kpi-blue)" />
          </Col>
          <Col xs={12} sm={6}>
            <MetricCard label="玩家端事件" value={chain.clientEventCount} accent="var(--kpi-blue)" />
          </Col>
          <Col xs={12} sm={6}>
            <MetricCard label="服务端事件" value={chain.serverEventCount} accent="var(--kpi-amber)" />
          </Col>
          <Col xs={12} sm={6}>
            <MetricCard label="关联 PTEID" value={chain.pteid} accent="var(--kpi-green)" />
          </Col>
        </Row>
      )}

      <Card
        title="时间线"
        size="small"
        style={{ marginBottom: 14 }}
        styles={{ body: { padding: chain?.items.length ? '4px 16px' : 24 } }}
      >
        {!pteid ? (
          <Empty description="输入 PTEID 查询双端证据" />
        ) : loading ? (
          <Empty description="加载中…" />
        ) : chain && chain.items.length > 0 ? (
          <List
            dataSource={chain.items}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            renderItem={(item) => <EvidenceRow item={item} />}
          />
        ) : (
          <Empty description="该 PTEID 暂无跨端证据" />
        )}
      </Card>

      <Card
        title={`设备指纹 (${chain?.devices.length ?? 0})`}
        size="small"
        styles={{ body: { padding: chain?.devices.length ? 0 : 24 } }}
      >
        {chain && chain.devices.length > 0 ? (
          <Table
            size="small"
            rowKey="deviceId"
            columns={deviceColumns}
            dataSource={chain.devices}
            pagination={false}
            scroll={{ x: 760 }}
            locale={{ emptyText: '暂无设备指纹' }}
          />
        ) : (
          <Empty description="暂无设备指纹" />
        )}
      </Card>
    </div>
  )
}

const deviceColumns: TableColumnsType<CrossEvidenceDevice> = [
  { title: '设备 ID', dataIndex: 'deviceId', width: 160 },
  {
    title: '指纹摘要',
    dataIndex: 'deviceFingerprint',
    render: (value?: string) => value
      ? <Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{value.slice(0, 16)}…{value.slice(-8)}</Text>
      : <Text type="secondary">-</Text>,
  },
  { title: '平台', dataIndex: 'platform', width: 100, render: (v?: string) => v || '-' },
  { title: '设备名', dataIndex: 'deviceName', render: (v?: string) => v || '-' },
  { title: '最近 IP', dataIndex: 'ip', width: 140, render: (v?: string) => v || '-' },
  { title: '最近登录', dataIndex: 'lastLoginAt', width: 170, render: (v?: string) => fmt(v) },
  {
    title: '状态',
    dataIndex: 'active',
    width: 90,
    render: (active: boolean) => active ? <Tag color="green">当前设备</Tag> : <Tag>历史</Tag>,
  },
]

function EvidenceRow({ item }: { item: CrossEvidenceItem }) {
  const source = SOURCE_META[item.source]
  const severity = item.severity ?? ''
  return (
    <List.Item style={{ alignItems: 'flex-start' }}>
      <div style={{ width: '100%' }}>
        <Space wrap size={8}>
          <Tag color={source.color}>{source.label}</Tag>
          <Text strong>{item.eventType || '未知事件'}</Text>
          {item.checkType && <Tag>{item.checkType}</Tag>}
          {severity && <Tag color={SEVERITY_COLOR[severity] ?? 'default'}>{severity}</Tag>}
          <Tag bordered={false}>风险 {item.riskScore}</Tag>
          {item.redscreenTriggered && <Tag color="red">联动红屏</Tag>}
          <Text type="secondary" style={{ fontSize: 12 }}>{fmt(item.occurredAt)}</Text>
        </Space>
        <div style={{ marginTop: 6, fontSize: 13, color: 'var(--muted)' }}>
          {item.detail || '无明细'}
        </div>
        {(item.processName || item.memoryRegion || item.signatureHit || item.clientVersion || item.osInfo) && (
          <Space wrap size={12} style={{ marginTop: 6, fontSize: 12 }}>
            {item.processName && <Text type="secondary">进程 {item.processName}</Text>}
            {item.memoryRegion && <Text type="secondary">内存 {item.memoryRegion}</Text>}
            {item.signatureHit && <Text type="secondary">签名 {item.signatureHit}</Text>}
            {item.clientVersion && <Text type="secondary">客户端 {item.clientVersion}</Text>}
            {item.osInfo && <Text type="secondary">系统 {item.osInfo}</Text>}
          </Space>
        )}
        <Space wrap size={12} style={{ marginTop: 6, fontSize: 12 }}>
          {item.serverId && <Text type="secondary">服务器 {item.serverId}</Text>}
          {item.playerName && <Text type="secondary">玩家 {item.playerName}</Text>}
          {item.alertId && (
            <Text type="secondary">
              <LinkOutlined /> {item.alertId}
            </Text>
          )}
          <Text type="secondary" style={{ fontFamily: 'monospace' }}>{item.eventId}</Text>
        </Space>
      </div>
    </List.Item>
  )
}

function fmt(s?: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}
