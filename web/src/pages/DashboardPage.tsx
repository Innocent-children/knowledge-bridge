import {type FC, useCallback, useEffect, useState} from 'react'
import {Alert, Button, Col, Row, Typography} from 'antd'
import {
    CheckCircleOutlined,
    CloudUploadOutlined,
    ReloadOutlined,
    ThunderboltOutlined
} from '@ant-design/icons'
import api from '../api/client'
import MetricCard from '../components/MetricCard'
import HealthStatusCard from '../components/HealthStatusCard'

const {Title} = Typography

/** Metric names fetched from the Actuator metrics API. */
const METRIC_NAMES = [
    'kb.query.count',
    'kb.ingest.task.count',
    'kb.ingest.success.rate',
] as const

/** Labels, units, and accent colors for each metric. */
const METRIC_CONFIG: Record<string, { label: string; unit: string; accent: string }> = {
    'kb.query.count': {label: '查询次数', unit: '次', accent: '#6BA3BE'},
    'kb.ingest.task.count': {label: '入库任务数', unit: '个', accent: '#D4A853'},
    'kb.ingest.success.rate': {label: '入库成功率', unit: '%', accent: '#5EC269'},
}

const METRIC_ICONS: Record<string, React.ReactNode> = {
    'kb.query.count': <ThunderboltOutlined/>,
    'kb.ingest.task.count': <CloudUploadOutlined/>,
    'kb.ingest.success.rate': <CheckCircleOutlined/>,
}

/** Shape of the actuator health response. */
interface HealthResponse {
    status: string
    components?: Record<string, unknown>
}

/** Shape of the actuator metrics response. */
export interface MetricResponse {
    name: string
    measurements: { statistic: string; value: number }[]
    availableTags: unknown[]
}

/** Parsed metric data used for rendering. */
export interface MetricData {
    name: string
    label: string
    value: number
    unit: string
    accent: string
}

const DashboardPage: FC = () => {
    const [metrics, setMetrics] = useState<MetricData[]>([])
    const [healthStatus, setHealthStatus] = useState<string | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    const fetchData = useCallback(async () => {
        setLoading(true)
        setError(null)

        try {
            const [healthRes, ...metricResults] = await Promise.all([
                api.get<HealthResponse>('/actuator/health'),
                ...METRIC_NAMES.map((name) =>
                    api.get<MetricResponse>(`/actuator/metrics/${name}`).catch(() => null)
                ),
            ])

            setHealthStatus(healthRes.status)

            const parsed: MetricData[] = []
            metricResults.forEach((res, idx) => {
                if (!res) return
                const metricName = METRIC_NAMES[idx]
                const config = METRIC_CONFIG[metricName]
                const measurement = res.measurements?.[0]
                parsed.push({
                    name: metricName,
                    label: config?.label ?? metricName,
                    value: measurement?.value ?? 0,
                    unit: config?.unit ?? '',
                    accent: config?.accent ?? '#D4A853',
                })
            })

            setMetrics(parsed)
        } catch {
            setError('获取仪表盘数据失败，请检查服务器连接。')
        } finally {
            setLoading(false)
        }
    }, [])

    useEffect(() => {
        fetchData()
    }, [fetchData])

    return (
        <div style={{padding: '28px 32px'}}>
            {/* Header */}
            <div
                style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    marginBottom: 28,
                }}
            >
                <div>
                    <Title
                        level={3}
                        style={{
                            margin: 0,
                            color: '#E8E6E1',
                            fontFamily: '"Playfair Display", serif',
                            fontWeight: 700,
                            letterSpacing: '-0.02em',
                        }}
                    >
                        仪表盘
                    </Title>
                    <div style={{fontSize: 13, color: '#6B6860', marginTop: 4}}>
                        系统运行状态概览
                    </div>
                </div>
                <Button
                    icon={<ReloadOutlined spin={loading}/>}
                    onClick={fetchData}
                    loading={loading}
                    aria-label="刷新指标"
                    style={{
                        borderColor: '#2E3240',
                        color: '#9B978F',
                        background: '#1E2128',
                    }}
                >
                    刷新
                </Button>
            </div>

            {error && (
                <Alert
                    message="获取数据失败"
                    description={error}
                    type="error"
                    showIcon
                    style={{marginBottom: 24}}
                    action={
                        <Button size="small" danger onClick={fetchData}>
                            重试
                        </Button>
                    }
                />
            )}

            {/* Loading skeleton */}
            {loading && !error && (
                <Row gutter={[20, 20]} className="stagger-in">
                    {[1, 2, 3, 4].map((i) => (
                        <Col xs={24} sm={12} lg={6} key={i}>
                            <div
                                className="shimmer"
                                style={{
                                    height: 120,
                                    borderRadius: 14,
                                    background: 'rgba(26, 29, 35, 0.7)',
                                    border: '1px solid rgba(255, 255, 255, 0.03)',
                                }}
                            />
                        </Col>
                    ))}
                </Row>
            )}

            {/* Metric cards */}
            {!loading && !error && (
                <Row gutter={[20, 20]} className="stagger-in">
                    <Col xs={24} sm={12} lg={6}>
                        <HealthStatusCard status={healthStatus}/>
                    </Col>
                    {metrics.map((metric) => (
                        <Col xs={24} sm={12} lg={6} key={metric.name}>
                            <MetricCard
                                name={metric.label}
                                value={metric.value}
                                unit={metric.unit}
                                accent={metric.accent}
                            />
                        </Col>
                    ))}
                    {metrics.length === 0 && (
                        <Col xs={24} sm={12} lg={6}>
                            <MetricCard name="暂无指标数据" value={0} unit=""/>
                        </Col>
                    )}
                </Row>
            )}

            {/* Decorative section divider */}
            {!loading && !error && (
                <div style={{marginTop: 40, marginBottom: 24}}>
                    <div className="accent-line"/>
                </div>
            )}

            {/* Quick stats summary */}
            {!loading && !error && metrics.length > 0 && (
                <div
                    style={{
                        display: 'flex',
                        gap: 32,
                        flexWrap: 'wrap',
                        padding: '20px 0',
                    }}
                    className="fade-in"
                >
                    {metrics.map((m) => (
                        <div key={m.name} style={{display: 'flex', alignItems: 'center', gap: 10}}>
              <span style={{color: m.accent, fontSize: 16}}>
                {METRIC_ICONS[m.name]}
              </span>
                            <span style={{fontSize: 13, color: '#6B6860'}}>{m.label}</span>
                            <span
                                style={{
                                    fontFamily: '"JetBrains Mono", monospace',
                                    fontSize: 13,
                                    color: '#9B978F',
                                    fontWeight: 500,
                                }}
                            >
                {m.unit === '%' ? `${m.value.toFixed(1)}%` : m.value.toLocaleString()}
              </span>
                        </div>
                    ))}
                </div>
            )}
        </div>
    )
}

export default DashboardPage
