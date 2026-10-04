package com.openclaw.kbbridge.dto.document;

import java.time.LocalDateTime;

/**
 * 文档详情响应 DTO。
 * <p>
 * 返回知识文档的完整信息，包括知识类型、标题、主题、标签、审核状态、
 * 文档状态、RAGFlow 数据集名称、RAGFlow 文档 ID、元数据和版本号。
 * </p>
 *
 * @param id                文档主键（kb_document.id）
 * @param taskId            关联的入库任务 ID
 * @param knowledgeType     知识类型，如 GUIDE / QA
 * @param title             文档标题
 * @param topic             文档主题
 * @param tagsJson          标签列表的 JSON 字符串
 * @param reviewStatus      审核状态（PENDING / APPROVED / REJECTED）
 * @param status            文档状态（枚举 DocumentStatus）
 * @param datasetName       RAGFlow 数据集名称
 * @param ragflowDocumentId RAGFlow 侧的文档 ID
 * @param metadataJson      完整元数据的 JSON 字符串
 * @param version           文档版本号，随更新递增
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record DocumentDetailResponse(
                // 文档主键
                Long id,
                // 关联的入库任务 ID（kb_ingest_task.id）
                Long taskId,
                // 知识类型：GUIDE（引导）/ QA（问答）
                String knowledgeType,
                // 文档标题
                String title,
                // 文档主题，用于聚合和检索过滤
                String topic,
                // 标签列表的 JSON 字符串（List<String> 序列化）
                String tagsJson,
                // 审核状态：PENDING / APPROVED / REJECTED
                String reviewStatus,
                // 文档状态（DocumentStatus 枚举名）
                String status,
                // RAGFlow 侧数据集名称
                String datasetName,
                // RAGFlow 侧的文档 ID，用于回调 RAGFlow 进行更新或删除
                String ragflowDocumentId,
                // 完整元数据 JSON 字符串
                String metadataJson,
                // 文档版本号，每次更新后递增
                Integer version,
                // 创建时间
                LocalDateTime createdAt,
                // 更新时间
                LocalDateTime updatedAt,
                String documentId,
                String releaseId,
                String objectKey,
                String contentPreview,
                String source) {
    public DocumentDetailResponse(Long id, Long taskId, String knowledgeType, String title, String topic,
            String tagsJson, String reviewStatus, String status, String datasetName, String ragflowDocumentId,
            String metadataJson, Integer version, LocalDateTime createdAt, LocalDateTime updatedAt,
            String documentId, String releaseId, String objectKey, String contentPreview) {
        this(id, taskId, knowledgeType, title, topic, tagsJson, reviewStatus, status, datasetName,
                ragflowDocumentId, metadataJson, version, createdAt, updatedAt, documentId, releaseId, objectKey, contentPreview, null);
    }
    public DocumentDetailResponse(Long id, Long taskId, String knowledgeType, String title, String topic,
            String tagsJson, String reviewStatus, String status, String datasetName, String ragflowDocumentId,
            String metadataJson, Integer version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, taskId, knowledgeType, title, topic, tagsJson, reviewStatus, status, datasetName,
                ragflowDocumentId, metadataJson, version, createdAt, updatedAt, null, null, null, null, null);
    }
}
