package com.openclaw.kbbridge.dto.ragflow;

import java.util.List;

/**
 * RAGFlow Memory 检索响应 DTO。
 *
 * @param chunks 检索结果块列表
 */
public record MemorySearchResponse(
                // Memory 检索命中的 chunk 列表
                List<MemoryChunk> chunks) {

        /**
         * Memory 检索结果块。
         *
         * @param content 命中的文本内容
         * @param score   相关度分数
         */
        public record MemoryChunk(
                        // 命中的文本内容
                        String content,
                        // 相关度分数
                        double score) {
        }
}
