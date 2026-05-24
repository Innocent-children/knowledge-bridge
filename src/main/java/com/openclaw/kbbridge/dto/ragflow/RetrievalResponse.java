package com.openclaw.kbbridge.dto.ragflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * RAGFlow 检索响应 DTO。
 * <p>
 * RAGFlow API 返回格式为 {"code": 0, "data": {"chunks": [...], "total": N}}。
 * 本 DTO 映射外层结构，通过 {@link Data#chunks()} 获取检索结果。
 *
 * @param code    响应码（0 表示成功）
 * @param data    响应数据
 * @param message 错误消息（失败时）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetrievalResponse(
                // 响应码，0 表示成功，非 0 表示失败
                Integer code,
                // 响应数据层，包含 chunks 和 total
                Data data,
                // 错误消息（失败时），成功时通常为 null
                String message) {

        /**
         * 便捷方法：获取 chunks 列表，兼容 data 为 null 的情况。
         */
        public List<Chunk> chunks() {
                if (data == null || data.chunks() == null) {
                        return List.of();
                }
                return data.chunks();
        }

        /**
         * 便捷工厂方法：从 chunk 列表创建成功响应（用于测试）。
         */
        public static RetrievalResponse ofChunks(List<Chunk> chunks) {
                return new RetrievalResponse(0, new Data(chunks, chunks.size()), null);
        }

        /**
         * 响应数据层。
         *
         * @param chunks 检索命中的 chunk 列表
         * @param total  命中总数（可能大于 chunks.size，取决于分页）
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(
                        // 检索命中的 chunk 列表
                        List<Chunk> chunks,
                        // 命中总数
                        int total) {
        }

        /**
         * 检索结果块。
         * <p>
         * 字段映射到 RAGFlow 的下划线命名风格。
         *
         * @param content      命中的文本内容
         * @param similarity   相关度分数（RAGFlow 字段名为 similarity）
         * @param documentName 来源文档名称（RAGFlow 字段名为 document_keyword）
         * @param datasetId    来源数据集 ID（RAGFlow 字段名为 kb_id）
         * @param documentId   来源文档 ID（RAGFlow 字段名为 document_id）
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Chunk(
                        // 命中的文本内容
                        String content,
                        // 相关度分数（RAGFlow similarity）
                        double similarity,
                        // 来源文档名称；JSON 字段名 document_keyword
                        @JsonProperty("document_keyword") String documentName,
                        // 来源数据集 ID；JSON 字段名 kb_id
                        @JsonProperty("kb_id") String datasetId,
                        // 来源文档 ID；JSON 字段名 document_id
                        @JsonProperty("document_id") String documentId) {

                /**
                 * 兼容旧代码：返回 similarity 作为 score。
                 */
                public double score() {
                        return similarity;
                }

                /**
                 * 兼容旧代码：返回空 metadata map。
                 */
                public Map<String, Object> metadata() {
                        return Map.of();
                }
        }
}
