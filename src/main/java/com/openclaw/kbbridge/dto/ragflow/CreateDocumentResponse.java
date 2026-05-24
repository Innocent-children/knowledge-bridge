package com.openclaw.kbbridge.dto.ragflow;

/**
 * RAGFlow 创建文档响应 DTO。
 *
 * @param documentId RAGFlow 返回的文档 ID
 * @param name       文档名称
 */
public record CreateDocumentResponse(
                // RAGFlow 侧返回的文档 ID，后续更新/删除需要
                String documentId,
                // 文档名称（回显）
                String name) {
}
