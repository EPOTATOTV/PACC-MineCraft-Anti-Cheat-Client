import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Card, Col, Empty, Input, List, Row, Space, Tag, Typography } from 'antd'
import { LinkOutlined, SearchOutlined } from '@ant-design/icons'
import PageHeader from '../components/PageHeader'
import MetricCard from '../components/MetricCard'
import { api } from '../api/client'
import type { CrossEvidenceChain, CrossEvidenceItem } from '../types'

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
    </div>
  )
}

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
