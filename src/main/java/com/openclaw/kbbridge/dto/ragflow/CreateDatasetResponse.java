package com.openclaw.kbbridge.dto.ragflow;

/**
 * RAGFlow 创建数据集响应 DTO。
 *
 * @param datasetId 数据集 ID
 * @param name      数据集名称
 */
public record CreateDatasetResponse(
                // RAGFlow 侧生成的数据集 ID
                String datasetId,
                // 数据集名称
                String name) {
}
