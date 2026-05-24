package com.openclaw.kbbridge.dto.ragflow;

/**
 * RAGFlow 更新文档响应 DTO。
 *
 * @param documentId 文档 ID
 * @param success    是否更新成功
 */
public record UpdateDocumentResponse(
                // 文档 ID（回显）
                String documentId,
                // 是否更新成功
                boolean success) {
}
