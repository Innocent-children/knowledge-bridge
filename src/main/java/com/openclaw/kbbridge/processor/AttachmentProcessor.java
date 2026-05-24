package com.openclaw.kbbridge.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 附件类型知识化重写处理器。
 * <p>
 * 接收附件的文本内容，根据内容特征判断下游处理器：
 * <ul>
 * <li>长文档/教程类（长度 > 500 且包含 Markdown 标题）→ 委托给 MarkdownKnowledgeProcessor</li>
 * <li>其他内容 → 委托给 FeishuQaProcessor（默认）</li>
 * </ul>
 * AttachmentProcessor 本身不执行 LLM 重写，仅负责内容分析和委托。
 * </p>
 */
@Slf4j
@Component("attachmentProcessor")
public class AttachmentProcessor implements KnowledgeProcessor {

    /**
     * 内容长度阈值，超过此值且包含 Markdown 标题时视为教程/长文档
     */
    private static final int LONG_CONTENT_THRESHOLD = 500;

    /**
     * 匹配 Markdown 标题行（以 # 开头）
     */
    private static final Pattern MARKDOWN_HEADER_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+.+");

    private final KnowledgeProcessor markdownKnowledgeProcessor;
    private final KnowledgeProcessor feishuQaProcessor;

    /**
     * 构造函数，注入两个具体的下游处理器。
     * <p>
     * 使用 @Qualifier 按 Bean 名称注入，避免注入整个 processorMap 导致循环依赖。
     * </p>
     *
     * @param markdownKnowledgeProcessor 教程/长文档处理器
     * @param feishuQaProcessor          单轮问答处理器
     */
    public AttachmentProcessor(
            @Qualifier("markdownKnowledgeProcessor") KnowledgeProcessor markdownKnowledgeProcessor,
            @Qualifier("feishuQaProcessor") KnowledgeProcessor feishuQaProcessor) {
        this.markdownKnowledgeProcessor = markdownKnowledgeProcessor;
        this.feishuQaProcessor = feishuQaProcessor;
        log.info("AttachmentProcessor 初始化完成");
    }

    /**
     * 处理附件内容：分析内容特征后委托给对应的下游处理器。
     *
     * @param rawContent 附件的文本内容（已抽取）
     * @param sourceType 来源类型（ATTACHMENT）
     * @param context    上下文信息（如 requestId、userId 等）
     * @return 下游处理器的处理结果
     */
    @Override
    public ProcessResult process(String rawContent, String sourceType, Map<String, Object> context) {
        int contentLength = rawContent != null ? rawContent.length() : 0;
        log.info("开始处理附件内容: sourceType={}, 内容长度={}", sourceType, contentLength);

        KnowledgeProcessor delegate = resolveDelegate(rawContent);
        String delegateName = delegate == markdownKnowledgeProcessor
                ? "markdownKnowledgeProcessor"
                : "feishuQaProcessor";
        log.info("附件内容分析完成, 委托给: {}", delegateName);

        ProcessResult result = delegate.process(rawContent, sourceType, context);
        log.info("附件内容处理完成: 委托处理器={}, Guide输出长度={}, Q&A输出长度={}",
                delegateName,
                result.guideContent() != null ? result.guideContent().length() : 0,
                result.qaContent() != null ? result.qaContent().length() : 0);

        return result;
    }

    /**
     * 根据内容特征选择下游处理器。
     * <p>
     * 判断逻辑：
     * <ul>
     * <li>内容长度 > 500 且包含 Markdown 标题（行首 #）→ MarkdownKnowledgeProcessor</li>
     * <li>其他情况 → FeishuQaProcessor（默认）</li>
     * </ul>
     * </p>
     *
     * @param content 附件文本内容
     * @return 选定的下游处理器
     */
    private KnowledgeProcessor resolveDelegate(String content) {
        if (content == null || content.isEmpty()) {
            log.info("附件内容为空, 使用默认处理器: feishuQaProcessor");
            return feishuQaProcessor;
        }

        boolean isLongContent = content.length() > LONG_CONTENT_THRESHOLD;
        boolean hasMarkdownHeaders = MARKDOWN_HEADER_PATTERN.matcher(content).find();

        if (isLongContent && hasMarkdownHeaders) {
            log.info("附件内容为教程/长文档类型: 长度={}, 包含Markdown标题=true", content.length());
            return markdownKnowledgeProcessor;
        }

        log.info("附件内容为普通Q&A类型: 长度={}, 包含Markdown标题={}", content.length(), hasMarkdownHeaders);
        return feishuQaProcessor;
    }
}
