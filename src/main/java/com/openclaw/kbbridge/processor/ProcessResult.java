package com.openclaw.kbbridge.processor;

import java.util.List;

/**
 * 知识化重写处理结果。
 *
 * @param guideContent     Guide 内容（可空，非教程类不生成）
 * @param qaContent        Q&A 内容
 * @param processorVersion 处理器版本
 * @param topic            主题（从内容中提取，可空）
 * @param tags             标签列表（从内容中提取，可空）
 */
public record ProcessResult(
        String guideContent,
        String qaContent,
        String processorVersion,
        String topic,
        List<String> tags) {

    /**
     * 兼容旧的三参数构造（不含 metadata）。
     */
    public ProcessResult(String guideContent, String qaContent, String processorVersion) {
        this(guideContent, qaContent, processorVersion, null, null);
    }
}
