import {type FC, useCallback, useEffect, useState} from 'react'
import {
    Alert,
    Button,
    Descriptions,
    Drawer,
    notification,
    Select,
    Space,
    Switch,
    Table,
    Tag,
    Typography,
} from 'antd'
import {FileTextOutlined, ReloadOutlined} from '@ant-design/icons'
import type {ColumnsType, TablePaginationConfig} from 'antd/es/table'
import api from '../api/client'
import type {KnowledgeDocument, PageResult} from '../types'
import EmptyState from '../components/EmptyState'

const {Title} = Typography

const STATUS_OPTIONS = ['SYNCED', 'DISABLED', 'FAILED', 'EFFECTIVE', 'PREPARING', 'INDEXING', 'WITHDRAWN', 'DELETED', 'SUPERSEDED'] as const

const STATUS_STYLE: Record<string, { bg: string; border: string; color: string }> = {
    SYNCED: {bg: 'rgba(94, 194, 105, 0.08)', border: 'rgba(94, 194, 105, 0.2)', color: '#5EC269'},
    DISABLED: {
        bg: 'rgba(138, 134, 128, 0.08)',
        border: 'rgba(138, 134, 128, 0.2)',
        color: '#8A8680'
    },
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

const KNOWLEDGE_TYPE_STYLE: Record<string, { bg: string; border: string; color: string }> = {
    GUIDE: {bg: 'rgba(168, 130, 212, 0.08)', border: 'rgba(168, 130, 212, 0.2)', color: '#A882D4'},
    QA: {bg: 'rgba(107, 163, 190, 0.08)', border: 'rgba(107, 163, 190, 0.2)', color: '#6BA3BE'},
    FAQ: {bg: 'rgba(212, 168, 83, 0.08)', border: 'rgba(212, 168, 83, 0.2)', color: '#D4A853'},
}

function renderTag(value: string, styleMap: Record<string, {
    bg: string;
    border: string;
    color: string
}>) {
    const s = styleMap[value]
    if (!s) return <Tag>{value}</Tag>
    return (
        <Tag style={{
            background: s.bg,
            border: `1px solid ${s.border}`,
            color: s.color,
            fontWeight: 500,
            fontSize: 12
        }}>
            {value}
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

function parseTags(tagsJson?: string): string[] {
    if (!tagsJson) return []
    try {
        const parsed = JSON.parse(tagsJson)
        return Array.isArray(parsed) ? parsed : []
    } catch {
        return []
    }
}

function isBlogDocument(document: KnowledgeDocument): boolean {
    if (document.source) return document.source === 'BLOG'
    if (!document.metadataJson) return false
    try {
        return JSON.parse(document.metadataJson)?.source === 'BLOG'
    } catch {
        return false
    }
}

function isEnabled(document: KnowledgeDocument): boolean {
    return document.documentId ? document.status === 'EFFECTIVE' : document.status !== 'DISABLED'
}

function isProcessing(document: KnowledgeDocument): boolean {
    return !!document.documentId && ['QUEUED', 'PREPARING', 'INDEXING'].includes(document.status)
}

const DocumentsPage: FC = () => {
    const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
    const [total, setTotal] = useState(0)
    const [current, setCurrent] = useState(1)
    const [pageSize, setPageSize] = useState(20)
    const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [togglingIds, setTogglingIds] = useState<Set<number>>(new Set())

    const [detailVisible, setDetailVisible] = useState(false)
    const [detailDoc, setDetailDoc] = useState<KnowledgeDocument | null>(null)
    const [detailLoading, setDetailLoading] = useState(false)

    const fetchDocuments = useCallback(
        async (page: number, size: number, status?: string) => {
            setLoading(true)
            setError(null)
            try {
                let url = `/api/v1/documents?page=${page}&size=${size}`
                if (status) url += `&status=${encodeURIComponent(status)}`
                const result = await api.get<PageResult<KnowledgeDocument>>(url)
                setDocuments(result.records)
                setTotal(result.total)
                setCurrent(result.current)
            } catch (err) {
                setError(err instanceof Error ? err.message : '获取文档列表失败')
            } finally {
                setLoading(false)
            }
        },
        [],
    )

    useEffect(() => {
        fetchDocuments(current, pageSize, statusFilter)
    }, [fetchDocuments, current, pageSize, statusFilter])

    const handleTableChange = (pagination: TablePaginationConfig) => {
        setCurrent(pagination.current ?? 1)
        setPageSize(pagination.pageSize ?? 20)
    }

    const handleStatusChange = (value: string | undefined) => {
        setStatusFilter(value || undefined)
        setCurrent(1)
    }

    const handleToggleEnabled = async (record: KnowledgeDocument) => {
        if (isBlogDocument(record)) return
        const isCurrentlyEnabled = isEnabled(record)
        const endpoint = isCurrentlyEnabled
            ? `/api/v1/document/${record.id}/disable`
            : `/api/v1/document/${record.id}/enable`

        setTogglingIds((prev) => new Set(prev).add(record.id))
        const newStatus = record.documentId
            ? (isCurrentlyEnabled ? 'WITHDRAWN' : 'PREPARING')
            : (isCurrentlyEnabled ? 'DISABLED' : 'SYNCED')
        setDocuments((prev) => prev.map((doc) => doc.id === record.id ? {
            ...doc,
            status: newStatus
        } : doc))

        try {
            await api.post<{ message: string }>(endpoint, {})
            if (record.documentId) await fetchDocuments(current, pageSize, statusFilter)
        } catch (err) {
            setDocuments((prev) => prev.map((doc) => doc.id === record.id ? {
                ...doc,
                status: record.status
            } : doc))
            notification.error({
                message: isCurrentlyEnabled ? '禁用失败' : '启用失败',
                description: err instanceof Error ? err.message : '操作失败',
            })
        } finally {
            setTogglingIds((prev) => {
                const next = new Set(prev);
                next.delete(record.id);
                return next
            })
        }
    }

    const handleRowClick = async (record: KnowledgeDocument) => {
        setDetailVisible(true)
        setDetailLoading(true)
        setDetailDoc(null)
        try {
            const detail = await api.get<KnowledgeDocument>(`/api/v1/document/${record.id}`)
            setDetailDoc(detail)
        } catch {
            setDetailDoc(record)
        } finally {
            setDetailLoading(false)
        }
    }

    const handleRetry = () => fetchDocuments(current, pageSize, statusFilter)

    const columns: ColumnsType<KnowledgeDocument> = [
        {
            title: '文档 ID', dataIndex: 'id', key: 'id', width: 100,
            render: (id: number) => (
                <span style={{
                    fontFamily: '"JetBrains Mono", monospace',
                    fontSize: 13,
                    color: '#D4A853'
                }}>#{id}</span>
            ),
        },
        {title: '标题', dataIndex: 'title', key: 'title', ellipsis: true, width: 200},
        {
            title: '知识类型', dataIndex: 'knowledgeType', key: 'knowledgeType', width: 120,
            render: (type: string) => renderTag(type, KNOWLEDGE_TYPE_STYLE),
        },
        {
            title: '主题', dataIndex: 'topic', key: 'topic', ellipsis: true, width: 160,
            render: (text: string) => <span style={{color: '#9B978F'}}>{text}</span>,
        },
        {
            title: '状态', dataIndex: 'status', key: 'status', width: 110,
            render: (status: string, record: KnowledgeDocument) => isProcessing(record)
                ? <Tag color="processing">处理中</Tag> : renderTag(status, STATUS_STYLE),
        },
        {
            title: '启用', key: 'enabled', width: 120,
            render: (_: unknown, record: KnowledgeDocument) => isBlogDocument(record)
                ? <Tag>在博客管理</Tag> : (
                <Switch
                    checked={isEnabled(record)}
                    disabled={!!record.documentId && (record.status === 'DELETED' || record.status === 'SUPERSEDED' || isProcessing(record))}
                    loading={togglingIds.has(record.id)}
                    onChange={(_, e) => {
                        e.stopPropagation();
                        handleToggleEnabled(record)
                    }}
                    data-testid={`toggle-${record.id}`}
                    size="small"
                />
            ),
        },
        {
            title: '审核状态', dataIndex: 'reviewStatus', key: 'reviewStatus', width: 120,
            render: (status: string) => renderTag(status, REVIEW_STATUS_STYLE),
        },
        {
            title: '版本', dataIndex: 'version', key: 'version', width: 80,
            render: (v: number) => (
                <span style={{
                    fontFamily: '"JetBrains Mono", monospace',
                    fontSize: 13,
                    color: '#8A8680'
                }}>v{v}</span>
            ),
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
                        文档管理
                    </Title>
                    <div style={{fontSize: 13, color: '#6B6860', marginTop: 4}}>
                        管理知识库中的文档
                    </div>
                </div>
                <Space>
                    <Select
                        placeholder="按状态筛选"
                        allowClear
                        style={{width: 160}}
                        value={statusFilter}
                        onChange={handleStatusChange}
                        data-testid="status-filter"
                        options={STATUS_OPTIONS.map((s) => ({label: s, value: s}))}
                    />
                    <Button
                        icon={<ReloadOutlined/>}
                        onClick={handleRetry}
                        loading={loading}
                        aria-label="刷新文档"
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
                    action={<Button size="small" danger onClick={handleRetry}
                                    data-testid="retry-button">重试</Button>}
                />
            )}

            <div style={{
                borderRadius: 14,
                overflow: 'hidden',
                border: '1px solid rgba(255, 255, 255, 0.04)'
            }}>
                <Table<KnowledgeDocument>
                    columns={columns}
                    dataSource={documents}
                    rowKey="id"
                    loading={loading && !error}
                    pagination={{
                        current, pageSize, total,
                        showSizeChanger: true,
                        pageSizeOptions: ['10', '20', '50', '100'],
                        showTotal: (t) => <span style={{color: '#6B6860'}}>共 {t} 条文档</span>,
                    }}
                    onChange={handleTableChange}
                    onRow={(record) => ({
                        onClick: () => handleRowClick(record),
                        style: {cursor: 'pointer'}
                    })}
                    locale={{
                        emptyText: (
                            <EmptyState
                                icon={<FileTextOutlined style={{fontSize: 48}}/>}
                                description={statusFilter ? `没有状态为"${statusFilter}"的文档` : '暂无文档'}
                            />
                        ),
                    }}
                    scroll={{x: 1150}}
                />
            </div>

            <Drawer
                title={
                    <span style={{fontFamily: '"JetBrains Mono", monospace'}}>
            文档详情 — #{detailDoc?.id ?? ''}
          </span>
                }
                open={detailVisible}
                onClose={() => setDetailVisible(false)}
                size="large"
                data-testid="detail-drawer"
            >
                {detailLoading ? (
                    <div style={{textAlign: 'center', padding: 32, color: '#6B6860'}}>加载中…</div>
                ) : detailDoc ? (
                    <Descriptions bordered column={1} size="small">
                        <Descriptions.Item label="文档 ID">{detailDoc.id}</Descriptions.Item>
                        <Descriptions.Item label="标题">{detailDoc.title}</Descriptions.Item>
                        {isBlogDocument(detailDoc) && <Descriptions.Item label="发布管理">在博客管理</Descriptions.Item>}
                        <Descriptions.Item
                            label="知识类型">{renderTag(detailDoc.knowledgeType, KNOWLEDGE_TYPE_STYLE)}</Descriptions.Item>
                        <Descriptions.Item label="主题">{detailDoc.topic}</Descriptions.Item>
                        <Descriptions.Item
                            label="状态">{renderTag(detailDoc.status, STATUS_STYLE)}</Descriptions.Item>
                        <Descriptions.Item
                            label="审核状态">{renderTag(detailDoc.reviewStatus, REVIEW_STATUS_STYLE)}</Descriptions.Item>
                        <Descriptions.Item label="版本">
                            <span
                                style={{fontFamily: '"JetBrains Mono", monospace'}}>v{detailDoc.version}</span>
                        </Descriptions.Item>
                        <Descriptions.Item label="任务 ID">{detailDoc.taskId}</Descriptions.Item>
                        {detailDoc.datasetName && (
                            <Descriptions.Item
                                label="数据集名称">{detailDoc.datasetName}</Descriptions.Item>
                        )}
                        {detailDoc.ragflowDocumentId && (
                            <Descriptions.Item label="RAGFlow 文档 ID">
                <span style={{fontFamily: '"JetBrains Mono", monospace', fontSize: 12}}>
                  {detailDoc.ragflowDocumentId}
                </span>
                            </Descriptions.Item>
                        )}
                        {detailDoc.tagsJson && (
                            <Descriptions.Item label="标签">
                                <Space wrap>
                                    {parseTags(detailDoc.tagsJson).map((tag) => (
                                        <Tag
                                            key={tag}
                                            style={{
                                                background: 'rgba(212, 168, 83, 0.06)',
                                                border: '1px solid rgba(212, 168, 83, 0.12)',
                                                color: '#D4A853',
                                            }}
                                        >
                                            {tag}
                                        </Tag>
                                    ))}
                                </Space>
                            </Descriptions.Item>
                        )}
                        {detailDoc.contentPreview !== undefined && detailDoc.contentPreview !== null && (
                            <Descriptions.Item label="内容预览">
                                <pre style={{margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere'}}>
                                    {detailDoc.contentPreview}
                                </pre>
                            </Descriptions.Item>
                        )}
                        {detailDoc.metadataJson && (
                            <Descriptions.Item label="元数据">
                <pre style={{
                    margin: 0, fontSize: 12, whiteSpace: 'pre-wrap',
                    fontFamily: '"JetBrains Mono", monospace', color: '#9B978F',
                }}>
                  {detailDoc.metadataJson}
                </pre>
                            </Descriptions.Item>
                        )}
                        <Descriptions.Item
                            label="创建时间">{formatDate(detailDoc.createdAt)}</Descriptions.Item>
                        <Descriptions.Item
                            label="更新时间">{formatDate(detailDoc.updatedAt)}</Descriptions.Item>
                    </Descriptions>
                ) : null}
            </Drawer>
        </div>
    )
}

export default DocumentsPage
