package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.exception.BizException;
import com.openclaw.kbbridge.processor.KnowledgeProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 入库内容路由器。
 * <p>
 * 根据 sourceType 和内容特征将原始内容分流到对应的知识化重写处理器：
 * <ul>
 * <li>教程/长文档（MARKDOWN、TUTORIAL、NOTE）→ MarkdownKnowledgeProcessor</li>
 * <li>单轮问答（FEISHU_CHAT）→ FeishuQaProcessor</li>
 * <li>附件（ATTACHMENT）→ AttachmentProcessor</li>
 * <li>未知类型 → 默认回退到 FeishuQaProcessor</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class IngestRouter {

    private static final int FEISHU_DOCUMENT_LENGTH_THRESHOLD = 500;
    private static final int FEISHU_LONG_DOCUMENT_LENGTH_THRESHOLD = 2000;
    private static final Pattern MARKDOWN_HEADER_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+.+");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("(?m)^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+).+");
    private static final Pattern TABLE_ROW_PATTERN = Pattern.compile("(?m)^\\s*\\|.+\\|\\s*$");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("(?m)^```");
    private static final Pattern QA_MARKER_PATTERN = Pattern.compile(
            "(?im)^\\s*(?:Q\\d*[:\\uFF1A]|A\\d*[:\\uFF1A]|\\u95ee\\u9898[:\\uFF1A]|\\u56de\\u7b54[:\\uFF1A]|\\u95ee[:\\uFF1A]|\\u7b54[:\\uFF1A]|##\\s*Q\\d+)");

    /**
     * MarkdownKnowledgeProcessor 的 Bean 名称
     */
    private static final String MARKDOWN_PROCESSOR = "markdownKnowledgeProcessor";
    /**
     * FeishuQaProcessor 的 Bean 名称
     */
    private static final String FEISHU_QA_PROCESSOR = "feishuQaProcessor";
    /**
     * AttachmentProcessor 的 Bean 名称
     */
    private static final String ATTACHMENT_PROCESSOR = "attachmentProcessor";
    /**
     * Spring 注入的所有 KnowledgeProcessor 实现，key 为 Bean 名称
     */
    private final Map<String, KnowledgeProcessor> processorMap;

    /**
     * 构造入库路由器，注入所有可用的 KnowledgeProcessor 实现。
     *
     * @param processorMap Spring 自动注入的处理器 Map（key 为 Bean 名称）
     */
    public IngestRouter(Map<String, KnowledgeProcessor> processorMap) {
        this.processorMap = processorMap;
        log.info("入库路由器初始化完成, 已注册处理器: {}", processorMap.keySet());
    }

    /**
     * 根据 sourceType 路由到对应的处理器。
     *
     * @param sourceType 来源类型（如 MARKDOWN、FEISHU_CHAT、ATTACHMENT）
     * @return 匹配的 KnowledgeProcessor 实现
     * @throws BizException 当目标处理器尚未注册时抛出
     */
    public KnowledgeProcessor route(String sourceType) {
        String beanName = resolveProcessorBeanName(sourceType, null);
        KnowledgeProcessor processor = processorMap.get(beanName);

        if (processor == null) {
            log.error("处理器 '{}' 未注册, sourceType={}, 已注册处理器: {}",
                    beanName, sourceType, processorMap.keySet());
            throw new BizException("处理器 '" + beanName + "' 尚未实现或未注册");
        }

        log.info("入库内容路由完成: sourceType={} → processor={}", sourceType, beanName);
        return processor;
    }

    public KnowledgeProcessor route(String sourceType, String content) {
        String beanName = resolveProcessorBeanName(sourceType, content);
        KnowledgeProcessor processor = processorMap.get(beanName);

        if (processor == null) {
            log.error("Processor '{}' is not registered, sourceType={}, registeredProcessors={}",
                    beanName, sourceType, processorMap.keySet());
            throw new BizException("Processor '" + beanName + "' is not registered");
        }

        log.info("Ingest route resolved: sourceType={}, processor={}", sourceType, beanName);
        return processor;
    }

    /**
     * 根据 sourceType 解析对应的处理器 Bean 名称。
     *
     * @param sourceType 来源类型
     * @return 处理器 Bean 名称
     */
    private String resolveProcessorBeanName(String sourceType) {
        return resolveProcessorBeanName(sourceType, null);
    }

    private String resolveProcessorBeanName(String sourceType, String content) {
        if (sourceType == null) {
            log.warn("sourceType 为空, 回退到默认处理器: {}", FEISHU_QA_PROCESSOR);
            return FEISHU_QA_PROCESSOR;
        }

        return switch (sourceType.toUpperCase()) {
            case "MARKDOWN", "TUTORIAL", "NOTE" -> MARKDOWN_PROCESSOR;
            case "FEISHU_CHAT" -> resolveFeishuProcessorBeanName(content);
            case "ATTACHMENT" -> ATTACHMENT_PROCESSOR;
            default -> {
                log.warn("未知的 sourceType '{}', 回退到默认处理器: {}", sourceType, FEISHU_QA_PROCESSOR);
                yield FEISHU_QA_PROCESSOR;
            }
        };
    }

    private String resolveFeishuProcessorBeanName(String content) {
        if (looksLikeDocument(content)) {
            log.info("FEISHU_CHAT content looks document-like, route to processor={}", MARKDOWN_PROCESSOR);
            return MARKDOWN_PROCESSOR;
        }
        log.info("FEISHU_CHAT content looks QA/chat-like, route to processor={}", FEISHU_QA_PROCESSOR);
        return FEISHU_QA_PROCESSOR;
    }

    private boolean looksLikeDocument(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        String normalized = content.strip();
        int length = normalized.length();
        if (length <= FEISHU_DOCUMENT_LENGTH_THRESHOLD) {
            return false;
        }

        if (looksLikeQaContent(normalized)) {
            return false;
        }

        if (MARKDOWN_HEADER_PATTERN.matcher(normalized).find()) {
            return true;
        }

        int paragraphCount = countParagraphs(normalized);
        int structureScore = 0;
        if (LIST_ITEM_PATTERN.matcher(normalized).find()) {
            structureScore++;
        }
        if (TABLE_ROW_PATTERN.matcher(normalized).find()) {
            structureScore++;
        }
        if (CODE_FENCE_PATTERN.matcher(normalized).find()) {
            structureScore++;
        }
        if (countHeadingLikeLines(normalized) >= 2) {
            structureScore++;
        }

        if (length > 1200 && paragraphCount >= 4 && structureScore >= 1) {
            return true;
        }

        return length > FEISHU_LONG_DOCUMENT_LENGTH_THRESHOLD
                && paragraphCount >= 6
                && !looksLikeQaContent(normalized);
    }

    private boolean looksLikeQaContent(String content) {
        int markerCount = 0;
        var matcher = QA_MARKER_PATTERN.matcher(content);
        while (matcher.find()) {
            markerCount++;
            if (markerCount >= 2) {
                return true;
            }
        }
        return false;
    }

    private int countParagraphs(String content) {
        int count = 0;
        for (String part : content.split("\\R\\s*\\R")) {
            if (!part.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private int countHeadingLikeLines(String content) {
        int count = 0;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 2
                    && trimmed.length() <= 80
                    && !trimmed.matches(".*[.!?;:,\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF0C\\uFF1A]$")) {
                count++;
                if (count >= 2) {
                    return count;
                }
            }
        }
        return count;
    }
}
