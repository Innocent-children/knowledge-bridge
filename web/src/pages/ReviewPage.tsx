import {type FC, useCallback, useEffect, useState} from 'react'
import {
    Alert,
    Button,
    Descriptions,
    Drawer,
    Input,
    message,
    Modal,
    Space,
    Table,
    Tabs,
    Tag,
    Typography,
} from 'antd'
import {
    AuditOutlined,
    CheckCircleOutlined,
    CloseCircleOutlined,
    ReloadOutlined,
} from '@ant-design/icons'
import type {ColumnsType, TablePaginationConfig} from 'antd/es/table'
import api from '../api/client'
import type {PageResult, ReviewDetail, ReviewTask} from '../types'
import EmptyState from '../components/EmptyState'

const {Title, Text} = Typography
const {TextArea} = Input

type ReviewTab = 'CANDIDATE' | 'APPROVED' | 'REJECTED'

const TAB_ITEMS = [
    {key: 'CANDIDATE' as ReviewTab, label: '待审核'},
    {key: 'APPROVED' as ReviewTab, label: '已通过'},
    {key: 'REJECTED' as ReviewTab, label: '已拒绝'},
]

const REVIEW_STATUS_STYLE: Record<string, { bg: string; border: string; color: string }> = {
    CANDIDATE: {
        bg: 'rgba(232, 184, 75, 0.08)',
        border: 'rgba(232, 184, 75, 0.2)',
        color: '#E8B84B'
    },
    APPROVED: {bg: 'rgba(94, 194, 105, 0.08)', border: 'rgba(94, 194, 105, 0.2)', color: '#5EC269'},
    REJECTED: {bg: 'rgba(224, 92, 92, 0.08)', border: 'rgba(224, 92, 92, 0.2)', color: '#E05C5C'},
}

function renderStatusTag(status: string) {
    const s = REVIEW_STATUS_STYLE[status]
    if (!s) return <Tag>{status}</Tag>
    return (
        <Tag style={{
            background: s.bg,
            border: `1px solid ${s.border}`,
            color: s.color,
            fontWeight: 500,
            fontSize: 12
        }}>
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

function canReview(task: ReviewTask): boolean {
    return task.reviewStatus === 'CANDIDATE' && (!task.operation || task.status === 'WAITING_REVIEW')
}

const ReviewPage: FC = () => {
    const [tasks, setTasks] = useState<ReviewTask[]>([])
    const [total, setTotal] = useState(0)
    const [current, setCurrent] = useState(1)
    const [pageSize, setPageSize] = useState(20)
    const [activeTab, setActiveTab] = useState<ReviewTab>('CANDIDATE')
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([])

    const [rejectModalVisible, setRejectModalVisible] = useState(false)
    const [rejectTaskId, setRejectTaskId] = useState<number | null>(null)
    const [rejectReason, setRejectReason] = useState('')
    const [rejectLoading, setRejectLoading] = useState(false)

    const [batchRejectModalVisible, setBatchRejectModalVisible] = useState(false)
    const [batchRejectReason, setBatchRejectReason] = useState('')
    const [batchLoading, setBatchLoading] = useState(false)

    const [detailVisible, setDetailVisible] = useState(false)
    const [detailData, setDetailData] = useState<ReviewDetail | null>(null)
    const [detailLoading, setDetailLoading] = useState(false)

    const fetchTasks = useCallback(
        async (page: number, size: number, tab: ReviewTab) => {
            setLoading(true)
            setError(null)
            try {
                const url = `/api/v1/review/pending?page=${page}&size=${size}`
                const result = await api.get<PageResult<ReviewTask>>(url)
                const filtered = tab === 'CANDIDATE'
                    ? result.records
                    : result.records.filter((t) => t.reviewStatus === tab)
                setTasks(filtered)
                setTotal(tab === 'CANDIDATE' ? result.total : filtered.length)
                setCurrent(result.current)
            } catch (err) {
                setError(err instanceof Error ? err.message : '获取审核任务失败')
            } finally {
                setLoading(false)
            }
        },
        [],
    )

    useEffect(() => {
        fetchTasks(current, pageSize, activeTab)
    }, [fetchTasks, current, pageSize, activeTab])

    const refreshList = () => {
        setSelectedRowKeys([])
        fetchTasks(current, pageSize, activeTab)
    }

    const handleTableChange = (pagination: TablePaginationConfig) => {
        setCurrent(pagination.current ?? 1)
        setPageSize(pagination.pageSize ?? 20)
    }

    const handleTabChange = (key: string) => {
        setActiveTab(key as ReviewTab)
        setCurrent(1)
        setSelectedRowKeys([])
    }

    const handleApprove = async (taskId: number, e?: React.MouseEvent) => {
        e?.stopPropagation()
        try {
            await api.post('/api/v1/review/approve', {taskId, reviewer: 'console'})
            message.success(`任务 #${taskId} 已通过`)
            refreshList()
        } catch (err) {
            message.error(err instanceof Error ? err.message : '通过失败')
        }
    }

    const openRejectModal = (taskId: number, e?: React.MouseEvent) => {
        e?.stopPropagation()
        setRejectTaskId(taskId)
        setRejectReason('')
        setRejectModalVisible(true)
    }

    const handleRejectSubmit = async () => {
        if (rejectTaskId === null) return
        setRejectLoading(true)
        try {
            await api.post('/api/v1/review/reject', {
                taskId: rejectTaskId,
                reviewer: 'console',
                comment: rejectReason || undefined
            })
            message.success(`任务 #${rejectTaskId} 已拒绝`)
            setRejectModalVisible(false)
            refreshList()
        } catch (err) {
            message.error(err instanceof Error ? err.message : '拒绝失败')
        } finally {
            setRejectLoading(false)
        }
    }

    const handleBatchApprove = async () => {
        setBatchLoading(true)
        try {
            const items = selectedRowKeys.map((key) => ({taskId: Number(key), action: 'APPROVE'}))
            await api.post('/api/v1/review/batch', {reviewer: 'console', items})
            message.success(`${selectedRowKeys.length} 个任务已通过`)
            refreshList()
        } catch (err) {
            message.error(err instanceof Error ? err.message : '批量通过失败')
        } finally {
            setBatchLoading(false)
        }
    }

    const openBatchRejectModal = () => {
        setBatchRejectReason('')
        setBatchRejectModalVisible(true)
    }

    const handleBatchRejectSubmit = async () => {
        setBatchLoading(true)
        try {
            const items = selectedRowKeys.map((key) => ({
                taskId: Number(key),
                action: 'REJECT',
                comment: batchRejectReason || undefined
            }))
            await api.post('/api/v1/review/batch', {reviewer: 'console', items})
            message.success(`${selectedRowKeys.length} 个任务已拒绝`)
            setBatchRejectModalVisible(false)
            refreshList()
        } catch (err) {
            message.error(err instanceof Error ? err.message : '批量拒绝失败')
        } finally {
            setBatchLoading(false)
        }
    }

    const handleRowClick = async (record: ReviewTask) => {
        setDetailVisible(true)
        setDetailLoading(true)
        setDetailData(null)
        try {
            const detail = await api.get<ReviewDetail>(`/api/v1/review/${record.taskId}`)
            setDetailData(detail)
        } catch {
            setDetailData({
                taskId: record.taskId,
                requestId: record.requestId,
                userId: record.userId,
                sourceType: record.sourceType,
                content: record.contentPreview,
                reviewStatus: record.reviewStatus,
                createdAt: record.createdAt,
                updatedAt: record.createdAt,
            })
        } finally {
            setDetailLoading(false)
        }
    }

    const columns: ColumnsType<ReviewTask> = [
        {
            title: '任务 ID', dataIndex: 'taskId', key: 'taskId', width: 90,
            render: (id: number) => (
                <span style={{
                    fontFamily: '"JetBrains Mono", monospace',
                    fontSize: 13,
                    color: '#D4A853'
                }}>#{id}</span>
            ),
        },
        {title: '请求 ID', dataIndex: 'requestId', key: 'requestId', ellipsis: true, width: 200},
        {title: '用户 ID', dataIndex: 'userId', key: 'userId', width: 120},
        {title: '来源类型', dataIndex: 'sourceType', key: 'sourceType', width: 120},
        {
            title: '内容预览', dataIndex: 'contentPreview', key: 'contentPreview', ellipsis: true,
            render: (text: string) => <span style={{color: '#9B978F'}}>{text}</span>,
        },
        {
            title: '审核状态', dataIndex: 'reviewStatus', key: 'reviewStatus', width: 130,
            render: renderStatusTag,
        },
        {
            title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
            render: (d: string) => <span
                style={{fontSize: 13, color: '#8A8680'}}>{formatDate(d)}</span>,
        },
        {
            title: '操作', key: 'actions', width: 180, fixed: 'right',
            render: (_: unknown, record: ReviewTask) => {
                if (record.reviewStatus !== 'CANDIDATE') return null
                if (!canReview(record)) return <Tag color="processing">处理中</Tag>
                return (
                    <Space>
                        <Button
                            type="primary"
                            size="small"
                            icon={<CheckCircleOutlined/>}
                            onClick={(e) => handleApprove(record.taskId, e)}
                            data-testid={`approve-btn-${record.taskId}`}
                            style={{borderRadius: 6}}
                        >
                            通过
                        </Button>
                        <Button
                            danger
                            size="small"
                            icon={<CloseCircleOutlined/>}
                            onClick={(e) => openRejectModal(record.taskId, e)}
                            data-testid={`reject-btn-${record.taskId}`}
                            style={{borderRadius: 6}}
                        >
                            拒绝
                        </Button>
                    </Space>
                )
            },
        },
    ]

    return (
        <div style={{padding: '28px 32px'}}>
            {/* Header */}
            <div style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 24
            }}>
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
                        审核任务
                    </Title>
                    <div style={{fontSize: 13, color: '#6B6860', marginTop: 4}}>
                        审核和管理待入库内容
                    </div>
                </div>
                <Button
                    icon={<ReloadOutlined/>}
                    onClick={refreshList}
                    loading={loading}
                    aria-label="刷新审核任务"
                    style={{borderColor: '#2E3240', color: '#9B978F', background: '#1E2128'}}
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
                    action={<Button size="small" danger onClick={refreshList}
                                    data-testid="retry-button">重试</Button>}
                />
            )}

            <Tabs activeKey={activeTab} onChange={handleTabChange} items={TAB_ITEMS}
                  data-testid="review-tabs"/>

            {/* Batch action bar */}
            {selectedRowKeys.length > 0 && (
                <div
                    data-testid="batch-action-bar"
                    style={{
                        marginBottom: 16,
                        padding: '10px 16px',
                        background: 'rgba(212, 168, 83, 0.04)',
                        border: '1px solid rgba(212, 168, 83, 0.1)',
                        borderRadius: 10,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 12,
                    }}
                >
                    <Text style={{color: '#D4A853', fontWeight: 500, fontSize: 13}}>
                        已选择 {selectedRowKeys.length} 个任务
                    </Text>
                    <Button
                        type="primary"
                        size="small"
                        icon={<CheckCircleOutlined/>}
                        loading={batchLoading}
                        onClick={handleBatchApprove}
                        data-testid="batch-approve-btn"
                        style={{borderRadius: 6}}
                    >
                        批量通过
                    </Button>
                    <Button
                        danger
                        size="small"
                        icon={<CloseCircleOutlined/>}
                        loading={batchLoading}
                        onClick={openBatchRejectModal}
                        data-testid="batch-reject-btn"
                        style={{borderRadius: 6}}
                    >
                        批量拒绝
                    </Button>
                </div>
            )}

            <div style={{
                borderRadius: 14,
                overflow: 'hidden',
                border: '1px solid rgba(255, 255, 255, 0.04)'
            }}>
                <Table<ReviewTask>
                    columns={columns}
                    dataSource={tasks}
                    rowKey="taskId"
                    loading={loading && !error}
                    rowSelection={
                        activeTab === 'CANDIDATE'
                            ? {selectedRowKeys, onChange: (keys) => setSelectedRowKeys(keys),
                                getCheckboxProps: (record) => ({disabled: !canReview(record)})}
                            : undefined
                    }
                    pagination={{
                        current, pageSize, total,
                        showSizeChanger: true,
                        pageSizeOptions: ['10', '20', '50', '100'],
                        showTotal: (t) => <span style={{color: '#6B6860'}}>共 {t} 条任务</span>,
                    }}
                    onChange={handleTableChange}
                    onRow={(record) => ({
                        onClick: () => handleRowClick(record),
                        style: {cursor: 'pointer'}
                    })}
                    locale={{
                        emptyText: (
                            <EmptyState
                                icon={<AuditOutlined style={{fontSize: 48}}/>}
                                description={
                                    activeTab === 'CANDIDATE' ? '暂无待审核任务'
                                        : activeTab === 'APPROVED' ? '暂无已通过任务'
                                            : '暂无已拒绝任务'
                                }
                            />
                        ),
                    }}
                    scroll={{x: 1120}}
                />
            </div>

            {/* Reject reason modal */}
            <Modal
                title={<span
                    style={{fontFamily: '"JetBrains Mono", monospace'}}>拒绝任务 #{rejectTaskId ?? ''}</span>}
                open={rejectModalVisible}
                onCancel={() => setRejectModalVisible(false)}
                onOk={handleRejectSubmit}
                confirmLoading={rejectLoading}
                okText="拒绝"
                okButtonProps={{danger: true}}
                data-testid="reject-modal"
            >
                <div style={{marginBottom: 8}}>
                    <Text style={{color: '#9B978F'}}>请填写拒绝原因（可选）：</Text>
                </div>
                <TextArea
                    rows={3}
                    value={rejectReason}
                    onChange={(e) => setRejectReason(e.target.value)}
                    placeholder="输入拒绝原因..."
                    data-testid="reject-reason-input"
                />
            </Modal>

            {/* Batch reject reason modal */}
            <Modal
                title={`批量拒绝 ${selectedRowKeys.length} 个任务`}
                open={batchRejectModalVisible}
                onCancel={() => setBatchRejectModalVisible(false)}
                onOk={handleBatchRejectSubmit}
                confirmLoading={batchLoading}
                okText="全部拒绝"
                okButtonProps={{danger: true}}
                data-testid="batch-reject-modal"
            >
                <div style={{marginBottom: 8}}>
                    <Text style={{color: '#9B978F'}}>请填写拒绝原因（可选）：</Text>
                </div>
                <TextArea
                    rows={3}
                    value={batchRejectReason}
                    onChange={(e) => setBatchRejectReason(e.target.value)}
                    placeholder="输入拒绝原因..."
                    data-testid="batch-reject-reason-input"
                />
            </Modal>

            {/* Detail drawer */}
            <Drawer
                title={
                    <span style={{fontFamily: '"JetBrains Mono", monospace'}}>
            审核详情 — 任务 #{detailData?.taskId ?? ''}
          </span>
                }
                open={detailVisible}
                onClose={() => setDetailVisible(false)}
                width={560}
                data-testid="detail-drawer"
            >
                {detailLoading ? (
                    <div style={{textAlign: 'center', padding: 32, color: '#6B6860'}}>加载中…</div>
                ) : detailData ? (
                    <Descriptions bordered column={1} size="small">
                        <Descriptions.Item label="任务 ID">{detailData.taskId}</Descriptions.Item>
                        <Descriptions.Item
                            label="请求 ID">{detailData.requestId}</Descriptions.Item>
                        <Descriptions.Item label="用户 ID">{detailData.userId}</Descriptions.Item>
                        <Descriptions.Item
                            label="来源类型">{detailData.sourceType}</Descriptions.Item>
                        <Descriptions.Item
                            label="审核状态">{renderStatusTag(detailData.reviewStatus)}</Descriptions.Item>
                        {detailData.reviewComment && (
                            <Descriptions.Item
                                label="审核意见">{detailData.reviewComment}</Descriptions.Item>
                        )}
                        <Descriptions.Item label="内容">
                            <div style={{
                                maxHeight: 300,
                                overflow: 'auto',
                                whiteSpace: 'pre-wrap',
                                wordBreak: 'break-word',
                                color: '#9B978F',
                                lineHeight: 1.7
                            }}>
                                {detailData.content}
                            </div>
                        </Descriptions.Item>
                        <Descriptions.Item
                            label="创建时间">{formatDate(detailData.createdAt)}</Descriptions.Item>
                        <Descriptions.Item
                            label="更新时间">{formatDate(detailData.updatedAt)}</Descriptions.Item>
                    </Descriptions>
                ) : null}
            </Drawer>
        </div>
    )
}

export default ReviewPage
