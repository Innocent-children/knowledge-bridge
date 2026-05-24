package com.openclaw.kbbridge.processor;

import java.util.Map;

/**
 * 知识化重写处理器接口。
 * <p>
 * 不同类型的原始内容（教程/长文档、单轮问答、附件等）由不同的处理器实现，
 * 通过 LLM 将原始内容转换为适合检索的知识形态（Guide / Q&A）。
 * </p>
 */
public interface KnowledgeProcessor {

    /**
     * 对原始内容执行知识化重写。
     *
     * @param rawContent 原始内容
     * @param sourceType 来源类型（如 MARKDOWN、FEISHU_CHAT、ATTACHMENT）
     * @param context    上下文信息（如 requestId、userId 等）
     * @return 处理结果，包含 Guide 内容、Q&A 内容和处理器版本
     */
    ProcessResult process(String rawContent, String sourceType, Map<String, Object> context);
}
