package com.openclaw.kbbridge.processor;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.util.MarkdownUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 教程/长文档知识化重写处理器。
 * <p>
 * 通过 LLM 将教程、长文档类原始内容重写为 Guide + Q&A 双轨输出：
 * <ul>
 * <li>Guide：按语义分块输出，每个 chunk 聚焦一个子主题，chunk 之间用 ---CHUNK--- 分隔</li>
 * <li>Q&A：拆解为多个独立的问答对，每个问答对自包含</li>
 * </ul>
 * 不编造原始内容中没有的信息，保留关键实体和步骤。
 * </p>
 */
@Slf4j
@Component("markdownKnowledgeProcessor")
public class MarkdownKnowledgeProcessor implements KnowledgeProcessor {

    private final LlmClient llmClient;
    private final String guidePromptTemplate;
    private final String qaPromptTemplate;
    private final String processorVersion;

    /**
     * 构造函数，注入 LLM 客户端并从 classpath 加载 Guide 和 Q&A prompt 模板。
     *
     * @param llmClient    LLM 客户端
     * @param kbProperties 统一配置
     */
    public MarkdownKnowledgeProcessor(LlmClient llmClient, KbProperties kbProperties) {
        this.llmClient = llmClient;
        this.processorVersion = kbProperties.getProcessor().getProcessorVersion();
        this.guidePromptTemplate = loadTemplate(kbProperties.getProcessor().getGuidePromptTemplate(), "Guide");
        this.qaPromptTemplate = loadTemplate(kbProperties.getProcessor().getQaPromptTemplate(), "Q&A");
        log.info("MarkdownKnowledgeProcessor 初始化完成, processorVersion={}", processorVersion);
    }

    /**
     * 对原始内容执行 Guide + Q&A 双轨知识化重写。
     *
     * @param rawContent 原始内容
     * @param sourceType 来源类型
     * @param context    上下文信息
     * @return 处理结果，guideContent 为 Guide 重写内容，qaContent 为 Q&A 重写内容
     */
    @Override
    public ProcessResult process(String rawContent, String sourceType, Map<String, Object> context) {
        int contentLength = rawContent != null ? rawContent.length() : 0;
        log.info("开始 Guide + Q&A 双轨重写: sourceType={}, 内容长度={}", sourceType, contentLength);

        // 用 <content> 标签包裹原始内容，与 system prompt 中的引用保持一致，防止 prompt injection
        String wrappedContent = "<content>\n" + rawContent + "\n</content>";

        // Guide 重写
        log.info("开始 Guide 重写...");
        String guideRaw = llmClient.complete(guidePromptTemplate, wrappedContent);
        log.info("Guide 重写完成: 输出长度={}", guideRaw != null ? guideRaw.length() : 0);

        // Q&A 重写
        log.info("开始 Q&A 重写...");
        String qaRaw = llmClient.complete(qaPromptTemplate, wrappedContent);
        log.info("Q&A 重写完成: 输出长度={}", qaRaw != null ? qaRaw.length() : 0);

        log.info("Guide + Q&A 双轨重写全部完成: sourceType={}, Guide输出长度={}, Q&A输出长度={}",
                sourceType,
                guideRaw != null ? guideRaw.length() : 0,
                qaRaw != null ? qaRaw.length() : 0);

        // 从 front matter 提取元数据，优先 Guide，回退到 Q&A，最后回退到正则提取
        String topic = extractTopic(guideRaw, qaRaw, rawContent);
        List<String> tags = extractTags(guideRaw, qaRaw, rawContent);

        // 去除 front matter 后存储纯正文（向量数据库不需要 YAML 头）
        String guideContent = MarkdownUtil.stripFrontMatter(guideRaw);
        String qaContent = MarkdownUtil.stripFrontMatter(qaRaw);

        return new ProcessResult(guideContent, qaContent, processorVersion, topic, tags);
    }

    /**
     * 提取主题。优先从 Guide front matter，回退到 Q&A front matter，最后回退到标题提取。
     */
    private String extractTopic(String guideContent, String qaContent, String rawContent) {
        // 优先从 front matter 提取
        String topic = MarkdownUtil.extractFrontMatterTopic(guideContent);
        if (topic != null) {
            return topic;
        }
        topic = MarkdownUtil.extractFrontMatterTopic(qaContent);
        if (topic != null) {
            return topic;
        }
        // 回退到标题提取
        List<String> headers = MarkdownUtil.extractHeaders(guideContent);
        if (!headers.isEmpty()) {
            return headers.getFirst();
        }
        headers = MarkdownUtil.extractHeaders(rawContent);
        return headers.isEmpty() ? null : headers.getFirst();
    }

    /**
     * 提取标签。优先从 Guide front matter，回退到 Q&A front matter，最后回退到关键词提取。
     */
    private List<String> extractTags(String guideContent, String qaContent, String rawContent) {
        // 优先从 front matter 提取
        List<String> tags = MarkdownUtil.extractFrontMatterTags(guideContent);
        if (!tags.isEmpty()) {
            return tags;
        }
        tags = MarkdownUtil.extractFrontMatterTags(qaContent);
        if (!tags.isEmpty()) {
            return tags;
        }
        // 回退到关键词提取
        String source = guideContent != null ? guideContent : rawContent;
        List<String> keywords = MarkdownUtil.extractKeywords(source);
        return keywords.size() > 10 ? keywords.subList(0, 10) : keywords;
    }

    /**
     * 从 classpath 加载 prompt 模板文件。
     *
     * @param templatePath 模板路径（如 classpath:prompts/guide-template.md）
     * @param templateName 模板名称（用于日志）
     * @return 模板内容字符串
     */
    private String loadTemplate(String templatePath, String templateName) {
        String resourcePath = templatePath.replace("classpath:", "");
        try (InputStream is = new ClassPathResource(resourcePath).getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.info("{} prompt 模板加载成功: path={}, 长度={}", templateName, templatePath, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 " + templateName + " prompt 模板: " + templatePath, e);
        }
    }
}
