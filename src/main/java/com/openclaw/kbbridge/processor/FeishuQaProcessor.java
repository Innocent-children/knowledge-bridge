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
 * 飞书单轮问答知识化重写处理器。
 * <p>
 * 通过 LLM 将单轮问答类原始内容重写为适合检索的 Q&A 格式。
 * 仅生成 Q&A 内容，不生成 Guide（guideContent 为 null）。
 * </p>
 */
@Slf4j
@Component("feishuQaProcessor")
public class FeishuQaProcessor implements KnowledgeProcessor {

    private final LlmClient llmClient;
    private final String qaPromptTemplate;
    private final String processorVersion;

    /**
     * 构造函数，注入 LLM 客户端并从 classpath 加载 Q&A prompt 模板。
     *
     * @param llmClient    LLM 客户端
     * @param kbProperties 统一配置
     */
    public FeishuQaProcessor(LlmClient llmClient, KbProperties kbProperties) {
        this.llmClient = llmClient;
        this.processorVersion = kbProperties.getProcessor().getProcessorVersion();
        this.qaPromptTemplate = loadTemplate(kbProperties.getProcessor().getQaPromptTemplate());
        log.info("FeishuQaProcessor 初始化完成, processorVersion={}", processorVersion);
    }

    /**
     * 对原始内容执行 Q&A 知识化重写。
     *
     * @param rawContent 原始内容
     * @param sourceType 来源类型
     * @param context    上下文信息
     * @return 处理结果，guideContent 为 null，qaContent 为 LLM 重写的 Q&A 内容
     */
    @Override
    public ProcessResult process(String rawContent, String sourceType, Map<String, Object> context) {
        log.info("开始 Q&A 重写: sourceType={}, 内容长度={}", sourceType,
                rawContent != null ? rawContent.length() : 0);

        // 用 <content> 标签包裹原始内容，与 system prompt 中的引用保持一致，防止 prompt injection
        String wrappedContent = "<content>\n" + rawContent + "\n</content>";
        String qaRaw = llmClient.complete(qaPromptTemplate, wrappedContent);

        log.info("Q&A 重写完成: 输出长度={}", qaRaw != null ? qaRaw.length() : 0);

        // 优先从 front matter 提取元数据，回退到正则提取
        String topic = extractTopic(qaRaw, rawContent);
        List<String> tags = extractTags(qaRaw, rawContent);

        // 去除 front matter 后存储纯正文（向量数据库不需要 YAML 头）
        String qaContent = MarkdownUtil.stripFrontMatter(qaRaw);

        return new ProcessResult(null, qaContent, processorVersion, topic, tags);
    }

    /**
     * 提取主题。优先从 front matter，回退到标题提取。
     */
    private String extractTopic(String qaContent, String rawContent) {
        // 优先从 front matter 提取
        String topic = MarkdownUtil.extractFrontMatterTopic(qaContent);
        if (topic != null) {
            return topic;
        }
        // 回退到标题提取
        List<String> headers = MarkdownUtil.extractHeaders(qaContent);
        if (!headers.isEmpty()) {
            return headers.getFirst();
        }
        headers = MarkdownUtil.extractHeaders(rawContent);
        return headers.isEmpty() ? null : headers.getFirst();
    }

    /**
     * 提取标签。优先从 front matter，回退到关键词提取（取前 10 个）。
     */
    private List<String> extractTags(String qaContent, String rawContent) {
        // 优先从 front matter 提取
        List<String> tags = MarkdownUtil.extractFrontMatterTags(qaContent);
        if (!tags.isEmpty()) {
            return tags;
        }
        // 回退到关键词提取
        String source = qaContent != null ? qaContent : rawContent;
        List<String> keywords = MarkdownUtil.extractKeywords(source);
        return keywords.size() > 10 ? keywords.subList(0, 10) : keywords;
    }

    /**
     * 从 classpath 加载 prompt 模板文件。
     *
     * @param templatePath 模板路径（如 classpath:prompts/qa-template.md）
     * @return 模板内容字符串
     */
    private String loadTemplate(String templatePath) {
        String resourcePath = templatePath.replace("classpath:", "");
        try (InputStream is = new ClassPathResource(resourcePath).getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.info("Q&A prompt 模板加载成功: path={}, 长度={}", templatePath, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 Q&A prompt 模板: " + templatePath, e);
        }
    }
}
