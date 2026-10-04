// ========== Chat Types ==========

export interface ChatRequest {
    question: string
}

export interface ChatResponse {
    answer: string | null
    route: string
    sources: EvidenceSource[]
    llmError: boolean
    errorMessage?: string
}

export interface EvidenceSource {
    dataset: string
    title: string
    content: string
    score: number
    metadata: Record<string, unknown>
}

// ========== Ingest Types ==========

export interface IngestTask {
    id: number
    requestId: string
    userId: string
    sourceType: string
    status: string
    reviewStatus: string
    rawObjectKey?: string
    processedGuideKey?: string
    processedQaKey?: string
    errorMessage?: string
    createdAt: string
    updatedAt: string
}

// ========== Document Types ==========

export interface KnowledgeDocument {
    id: number
    taskId: number
    knowledgeType: string
    title: string
    topic: string
    tagsJson?: string
    status: string
    reviewStatus: string
    datasetName?: string
    ragflowDocumentId?: string
    metadataJson?: string
    source?: string
    documentId?: string
    releaseId?: string
    objectKey?: string
    contentPreview?: string
    version: number
    createdAt: string
    updatedAt: string
}

// ========== Pagination ==========

export interface PageResult<T> {
    records: T[]
    total: number
    current: number
    size: number
    pages: number
}

// ========== Review Types ==========

export interface ReviewTask {
    taskId: number
    requestId: string
    userId: string
    sourceType: string
    contentPreview: string
    reviewStatus: string
    status?: string
    operation?: string
    createdAt: string
}

export interface ReviewDetail {
    taskId: number
    requestId: string
    userId: string
    sourceType: string
    content: string
    reviewStatus: string
    reviewComment?: string
    createdAt: string
    updatedAt: string
}

// ========== Chat State ==========

export interface ChatState {
    messages: ChatMessage[]
    mode: 'query' | 'ingest'
    loading: boolean
}

export interface ChatMessage {
    id: string
    role: 'user' | 'assistant' | 'system'
    content: string
    sources?: EvidenceSource[]
    timestamp: number
    error?: boolean
}

// ========== API Error ==========

export interface ApiError {
    status: number
    message: string
}
