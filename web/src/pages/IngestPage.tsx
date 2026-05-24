import {type FC, useCallback, useEffect, useState} from 'react'
import {Alert, Button, Descriptions, Modal, Select, Space, Table, Tag, Typography,} from 'antd'
import {CloudUploadOutlined, ReloadOutlined} from '@ant-design/icons'
import type {ColumnsType, TablePaginationConfig} from 'antd/es/table'
import api from '../api/client'
import type {IngestTask, PageResult} from '../types'
import EmptyState from '../components/EmptyState'

const {Title} = Typography

const STATUS_OPTIONS = [
    'RECEIVED',
    'RAW_STORED',
    'PROCESSING',
    'PROCESSED_READY',
    'SYNC_PENDING',
    'SYNCED',
    'FAILED',
] as const

const STATUS_STYLE: Record<string, { bg: string; border: string; color: string }> = {
    RECEIVED: {
        bg: 'rgba(107, 163, 190, 0.08)',
        border: 'rgba(107, 163, 190, 0.2)',
        color: '#6BA3BE'
    },
    RAW_STORED: {
        bg: 'rgba(107, 163, 190, 0.06)',
        border: 'rgba(107, 163, 190, 0.15)',
        color: '#7BB5C8'
    },
    PROCESSING: {
        bg: 'rgba(212, 168, 83, 0.08)',
        border: 'rgba(212, 168, 83, 0.2)',
        color: '#D4A853'
    },
    PROCESSED_READY: {
        bg: 'rgba(168, 130, 212, 0.08)',
        border: 'rgba(168, 130, 212, 0.2)',
        color: '#A882D4'
    },
    SYNC_PENDING: {
        bg: 'rgba(232, 184, 75, 0.08)',
        border: 'rgba(232, 184, 75, 0.2)',
        color: '#E8B84B'
    },
    SYNCED: {bg: 'rgba(94, 194, 105, 0.08)', border: 'rgba(94, 194, 105, 0.2)', color: '#5EC269'},
    FAILED: {bg: 'rgba(224, 92, 92, 0.08)', border: 'rgba(224, 92, 92, 0.2)', color: '#E05C5C'},
}

const REVIEW_STATUS_STYLE: Record<string, { bg: string; border: string; color: string }> = {
    CANDIDATE: {
        bg: 'rgba(232, 184, 75, 0.08)',
        border: 'rgba(232, 184, 75, 0.2)',
        color: '#E8B84B'
    },
    APPROVED: {bg: 'rgba(94, 194, 105, 0.08)', border: 'rgba(94, 194, 105, 0.2)', color: '#5EC269'},
    REJECTED: {bg: 'rgba(224, 92, 92, 0.08)', border: 'rgba(224, 92, 92, 0.2)', color: '#E05C5C'},
}

function renderStatusTag(status: string, styleMap: Record<string, {
    bg: string;
    border: string;
    color: string
}>) {
    const s = styleMap[status]
    if (!s) return <Tag>{status}</Tag>
    return (
        <Tag
            style={{
                background: s.bg,
                border: `1px solid ${s.border}`,
                color: s.color,
                fontWeight: 500,
                fontSize: 12,
            }}
        >
            {status}
        </Tag>
    )
}

function formatDate(dateStr: string): string {
    if (!dateStr) return '—'
    try {
        return new Date(dateStr).toLocaleString()
    } catch {
        return dateStr
    }
}

const IngestPage: FC = () => {
    const [tasks, setTasks] = useState<IngestTask[]>([])
    const [total, setTotal] = useState(0)
    const [current, setCurrent] = useState(1)
    const [pageSize, setPageSize] = useState(20)
    const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const [detailVisible, setDetailVisible] = useState(false)
    const [detailTask, setDetailTask] = useState<IngestTask | null>(null)
    const [detailLoading, setDetailLoading] = useState(false)

    const fetchTasks = useCallback(
        async (page: number, size: number, status?: string) => {
            setLoading(true)
            setError(null)
            try {
                let url = `/api/v1/ingest/tasks?page=${page}&size=${size}`
                if (status) url += `&status=${encodeURIComponent(status)}`
                const result = await api.get<PageResult<IngestTask>>(url)
                setTasks(result.records)
                setTotal(result.total)
                setCurrent(result.current)
            } catch (err) {
                setError(err instanceof Error ? err.message : '获取入库任务失败')
            } finally {
                setLoading(false)
            }
        },
        [],
    )

    useEffect(() => {
        fetchTasks(current, pageSize, statusFilter)
    }, [fetchTasks, current, pageSize, statusFilter])

    const handleTableChange = (pagination: TablePaginationConfig) => {
        setCurrent(pagination.current ?? 1)
        setPageSize(pagination.pageSize ?? 20)
    }

    const handleStatusChange = (value: string | undefined) => {
        setStatusFilter(value || undefined)
        setCurrent(1)
    }

    const handleRowClick = async (record: IngestTask) => {
        setDetailVisible(true)
        setDetailLoading(true)
        setDetailTask(null)
        try {
            const detail = await api.get<IngestTask>(`/api/v1/ingest/status/${record.id}`)
            setDetailTask(detail)
        } catch {
            setDetailTask(record)
        } finally {
            setDetailLoading(false)
        }
    }

    const handleRetry = () => fetchTasks(current, pageSize, statusFilter)

    const columns: ColumnsType<IngestTask> = [
        {
            title: '任务 ID', dataIndex: 'id', key: 'id', width: 90,
            render: (id: number) => (
                <span style={{
                    fontFamily: '"JetBrains Mono", monospace',
                    fontSize: 13,
                    color: '#D4A853'
                }}>
          #{id}
        </span>
            ),
        },
        {title: '请求 ID', dataIndex: 'requestId', key: 'requestId', ellipsis: true, width: 200},
        {title: '来源类型', dataIndex: 'sourceType', key: 'sourceType', width: 120},
        {
            title: '状态', dataIndex: 'status', key: 'status', width: 140,
            render: (status: string) => renderStatusTag(status, STATUS_STYLE),
        },
        {
            title: '审核状态', dataIndex: 'reviewStatus', key: 'reviewStatus', width: 130,
            render: (status: string) => renderStatusTag(status, REVIEW_STATUS_STYLE),
        },
        {
            title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
            render: (d: string) => <span
                style={{fontSize: 13, color: '#8A8680'}}>{formatDate(d)}</span>,
        },
        {
            title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180,
            render: (d: string) => <span
                style={{fontSize: 13, color: '#8A8680'}}>{formatDate(d)}</span>,
        },
    ]

    return (
        <div style={{padding: '28px 32px'}}>
            {/* Header */}
            <div
                style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    marginBottom: 24,
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
                        入库任务
                    </Title>
                    <div style={{fontSize: 13, color: '#6B6860', marginTop: 4}}>
                        管理和监控知识入库流程
                    </div>
                </div>
                <Space>
                    <Select
                        placeholder="按状态筛选"
                        allowClear
                        style={{width: 180}}
                        value={statusFilter}
                        onChange={handleStatusChange}
                        data-testid="status-filter"
                        options={STATUS_OPTIONS.map((s) => ({label: s, value: s}))}
                        popupClassName="dark-select-dropdown"
                    />
                    <Button
                        icon={<ReloadOutlined/>}
                        onClick={handleRetry}
                        loading={loading}
                        aria-label="刷新入库任务"
                        style={{borderColor: '#2E3240', color: '#9B978F', background: '#1E2128'}}
                    >
                        刷新
                    </Button>
                </Space>
            </div>

            {error && (
                <Alert
                    message="获取数据失败"
                    description={error}
                    type="error"
                    showIcon
                    style={{marginBottom: 24}}
                    action={
                        <Button size="small" danger onClick={handleRetry}
                                data-testid="retry-button">
                            重试
                        </Button>
                    }
                />
            )}

            <div
                style={{
                    borderRadius: 14,
                    overflow: 'hidden',
                    border: '1px solid rgba(255, 255, 255, 0.04)',
                }}
            >
                <Table<IngestTask>
                    columns={columns}
                    dataSource={tasks}
                    rowKey="id"
                    loading={loading && !error}
                    pagination={{
                        current,
                        pageSize,
                        total,
                        showSizeChanger: true,
                        pageSizeOptions: ['10', '20', '50', '100'],
                        showTotal: (t) => <span style={{color: '#6B6860'}}>共 {t} 条任务</span>,
                    }}
                    onChange={handleTableChange}
                    onRow={(record) => ({
                        onClick: () => handleRowClick(record),
                        style: {cursor: 'pointer'},
                    })}
                    locale={{
                        emptyText: (
                            <EmptyState
                                icon={<CloudUploadOutlined style={{fontSize: 48}}/>}
                                description={
                                    statusFilter
                                        ? `没有状态为"${statusFilter}"的任务`
                                        : '暂无入库任务'
                                }
                            />
                        ),
                    }}
                    scroll={{x: 1040}}
                />
            </div>

            <Modal
                title={
                    <span style={{fontFamily: '"JetBrains Mono", monospace'}}>
            入库任务 #{detailTask?.id ?? ''}
          </span>
                }
                open={detailVisible}
                onCancel={() => setDetailVisible(false)}
                footer={null}
                width={640}
                data-testid="detail-modal"
            >
                {detailLoading ? (
                    <div style={{textAlign: 'center', padding: 32, color: '#6B6860'}}>加载中…</div>
                ) : detailTask ? (
                    <Descriptions bordered column={1} size="small">
                        <Descriptions.Item label="任务 ID">{detailTask.id}</Descriptions.Item>
                        <Descriptions.Item
                            label="请求 ID">{detailTask.requestId}</Descriptions.Item>
                        <Descriptions.Item label="用户 ID">{detailTask.userId}</Descriptions.Item>
                        <Descriptions.Item
                            label="来源类型">{detailTask.sourceType}</Descriptions.Item>
                        <Descriptions.Item label="状态">
                            {renderStatusTag(detailTask.status, STATUS_STYLE)}
                        </Descriptions.Item>
                        <Descriptions.Item label="审核状态">
                            {renderStatusTag(detailTask.reviewStatus, REVIEW_STATUS_STYLE)}
                        </Descriptions.Item>
                        {detailTask.rawObjectKey && (
                            <Descriptions.Item label="原始件路径">
                <span style={{fontFamily: '"JetBrains Mono", monospace', fontSize: 12}}>
                  {detailTask.rawObjectKey}
                </span>
                            </Descriptions.Item>
                        )}
                        {detailTask.processedGuideKey && (
                            <Descriptions.Item label="Guide 处理件路径">
                <span style={{fontFamily: '"JetBrains Mono", monospace', fontSize: 12}}>
                  {detailTask.processedGuideKey}
                </span>
                            </Descriptions.Item>
                        )}
                        {detailTask.processedQaKey && (
                            <Descriptions.Item label="Q&A 处理件路径">
                <span style={{fontFamily: '"JetBrains Mono", monospace', fontSize: 12}}>
                  {detailTask.processedQaKey}
                </span>
                            </Descriptions.Item>
                        )}
                        {detailTask.errorMessage && (
                            <Descriptions.Item label="错误信息">
                                <span style={{color: '#E05C5C'}}>{detailTask.errorMessage}</span>
                            </Descriptions.Item>
                        )}
                        <Descriptions.Item
                            label="创建时间">{formatDate(detailTask.createdAt)}</Descriptions.Item>
                        <Descriptions.Item
                            label="更新时间">{formatDate(detailTask.updatedAt)}</Descriptions.Item>
                    </Descriptions>
                ) : null}
            </Modal>
        </div>
    )
}

export default IngestPage
