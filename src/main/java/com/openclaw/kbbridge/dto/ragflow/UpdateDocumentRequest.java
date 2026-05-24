package com.openclaw.kbbridge.dto.ragflow;

/**
 * RAGFlow 更新文档请求 DTO。
 *
 * @param datasetId  数据集 ID
 * @param documentId 文档 ID
 * @param name       文档名称
 * @param status     文档状态
 */
public record UpdateDocumentRequest(
                // 所属数据集 ID
                String datasetId,
                // 要更新的文档 ID（RAGFlow 侧）
                String documentId,
                // 新的文档名称
                String name,
                // 新的文档状态
                String status) {
}
