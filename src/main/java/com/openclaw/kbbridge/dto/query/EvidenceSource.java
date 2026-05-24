package com.openclaw.kbbridge.dto.query;

import java.util.Map;

/**
 * 单条知识证据。
 *
 * @param dataset  来源数据集名称（如 kb_qa、kb_guide）
 * @param title    文档标题
 * @param content  证据内容（可能已截断）
 * @param score    检索相关度分数
 * @param metadata 元数据（topic、reviewStatus、knowledgeType 等）
 */
public record EvidenceSource(
                // 来源数据集名称，如 kb_qa、kb_guide
                String dataset,
                // 来源文档标题
                String title,
                // 证据正文内容，超过 maxContentLength 会被截断
                String content,
                // 检索相关度分数（RAGFlow similarity）
                double score,
                // 元数据：topic、reviewStatus、knowledgeType、documentId 等
                Map<String, Object> metadata) {
}
