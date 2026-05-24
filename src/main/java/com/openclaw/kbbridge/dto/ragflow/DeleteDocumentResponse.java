package com.openclaw.kbbridge.dto.ragflow;

/**
 * RAGFlow 删除文档响应 DTO。
 *
 * @param success 是否删除成功
 */
public record DeleteDocumentResponse(
                // 是否删除成功
                boolean success) {
}
