package com.openclaw.kbbridge.dto.ragflow;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * RAGFlow 检索请求 DTO。
 * <p>
 * 字段名使用 @JsonProperty 映射到 RAGFlow API 的下划线命名风格。
 *
 * @param question            用户问题
 * @param datasetIds          要检索的数据集 ID 列表
 * @param topK                向量检索参与计算的 chunk 数量（默认 1024）
 * @param similarityThreshold 最低相似度分数阈值（默认 0.2）
 * @param metadataCondition   元数据过滤条件（可空）
 */
public record RetrievalRequest(
                // 用户问题文本，作为检索 query
                String question,
                // 要检索的数据集 ID 列表；JSON 字段名 dataset_ids
                @JsonProperty("dataset_ids") List<String> datasetIds,
                // 向量检索参与计算的 chunk 数量（默认 1024）；JSON 字段名 top_k
                @JsonProperty("top_k") int topK,
                // 最低相似度分数阈值（默认 0.2）；JSON 字段名 similarity_threshold
                @JsonProperty("similarity_threshold") double similarityThreshold,
                // 元数据过滤条件，可为 null；JSON 字段名 metadata_condition
                @JsonProperty("metadata_condition") Map<String, Object> metadataCondition) {
}
