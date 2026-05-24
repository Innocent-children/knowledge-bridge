package com.openclaw.kbbridge.dto.ragflow;

import java.util.Map;

/**
 * RAGFlow 创建文档请求 DTO。
 *
 * @param datasetId 目标数据集 ID
 * @param name      文档名称
 * @param content   文档内容（文本）
 * @param metadata  文档元数据（可空）
 */
public record CreateDocumentRequest(
                // 目标数据集 ID
                String datasetId,
                // 文档名称（RAGFlow 侧显示名）
                String name,
                // 文档正文文本内容
                String content,
                // 文档元数据键值对（topic、tags 等），可为空
                Map<String, Object> metadata) {
}
