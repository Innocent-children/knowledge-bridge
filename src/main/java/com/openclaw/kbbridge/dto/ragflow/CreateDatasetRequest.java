package com.openclaw.kbbridge.dto.ragflow;

/**
 * RAGFlow 创建数据集请求 DTO。
 *
 * @param name        数据集名称
 * @param description 数据集描述
 */
public record CreateDatasetRequest(
                // 要创建的数据集名称
                String name,
                // 数据集描述，可为空
                String description) {
}
