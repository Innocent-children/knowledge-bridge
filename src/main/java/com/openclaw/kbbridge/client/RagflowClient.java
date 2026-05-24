package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.dto.ragflow.*;

/**
 * RAGFlow 客户端接口，封装对 RAGFlow 服务的所有 HTTP 调用。
 * <p>
 * 所有方法遵循统一约束：超时控制、异常转换为 ExternalServiceException、
 * requestId 透传（X-Request-Id 请求头）、日志脱敏（内容前 100 字摘要）。
 * 幂等接口支持重试（最多 3 次，间隔 1s）。
 * </p>
 */
public interface RagflowClient {

    /**
     * 调用 RAGFlow 检索接口（幂等操作，支持重试）。
     *
     * @param request   检索请求
     * @param requestId 请求唯一标识，用于透传和日志追踪
     * @return 检索响应
     */
    RetrievalResponse retrieval(RetrievalRequest request, String requestId);

    /**
     * 在 RAGFlow 数据集中创建/注册文档（幂等操作，支持重试）。
     *
     * @param request   创建文档请求，包含 datasetId、name、content、metadata
     * @param requestId 请求唯一标识，用于透传和日志追踪
     * @return 创建文档响应，包含 documentId 和 name
     */
    CreateDocumentResponse createDocument(CreateDocumentRequest request, String requestId);

    /**
     * 创建 RAGFlow 数据集（幂等操作，支持重试）。
     *
     * @param request   创建数据集请求，包含 name、description
     * @param requestId 请求唯一标识，用于透传和日志追踪
     * @return 创建数据集响应，包含 datasetId 和 name
     */
    CreateDatasetResponse createDataset(CreateDatasetRequest request, String requestId);

    /**
     * 调用 RAGFlow Memory 检索接口（幂等操作，支持重试）。
     *
     * @param request   Memory 检索请求，包含 question、datasetId
     * @param requestId 请求唯一标识，用于透传和日志追踪
     * @return Memory 检索响应，包含 chunks 列表
     */
    MemorySearchResponse searchMemory(MemorySearchRequest request, String requestId);

    /**
     * 删除 RAGFlow 数据集中的文档（幂等操作，支持重试）。
     *
     * @param datasetId  数据集 ID
     * @param documentId 文档 ID
     * @param requestId  请求唯一标识，用于透传和日志追踪
     * @return 删除文档响应
     */
    DeleteDocumentResponse deleteDocument(String datasetId, String documentId, String requestId);

    /**
     * 更新 RAGFlow 数据集中的文档（幂等操作，支持重试）。
     *
     * @param request   更新文档请求，包含 datasetId、documentId、name、status
     * @param requestId 请求唯一标识，用于透传和日志追踪
     * @return 更新文档响应
     */
    UpdateDocumentResponse updateDocument(UpdateDocumentRequest request, String requestId);
}
