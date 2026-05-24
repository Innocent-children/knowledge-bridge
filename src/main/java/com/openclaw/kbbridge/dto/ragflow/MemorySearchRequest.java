package com.openclaw.kbbridge.dto.ragflow;

/**
 * RAGFlow Memory 检索请求 DTO。
 *
 * @param question  用户问题
 * @param datasetId 数据集 ID
 */
public record MemorySearchRequest(
                // 用户问题（Memory 检索的 query）
                String question,
                // 目标数据集 ID
                String datasetId) {
}
