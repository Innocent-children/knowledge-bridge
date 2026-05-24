package com.openclaw.kbbridge.processor;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FeishuQaProcessor 单元测试。
 * <p>
 * 使用 Mockito mock LlmClient，验证 Q&A 重写处理器的核心行为。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class FeishuQaProcessorTest {

    private static final String PROCESSOR_VERSION = "v1";
    @Mock
    private LlmClient llmClient;
    private FeishuQaProcessor processor;

    @BeforeEach
    void setUp() {
        KbProperties kbProperties = new KbProperties();
        KbProperties.Processor processorConfig = kbProperties.getProcessor();
        processorConfig.setProcessorVersion(PROCESSOR_VERSION);
        processorConfig.setQaPromptTemplate("classpath:prompts/qa-template.md");

        processor = new FeishuQaProcessor(llmClient, kbProperties);
    }

    @Test
    void successfulProcessingReturnsQaContentAndNullGuide() {
        // 准备
        String rawContent = "如何配置 Spring Boot 的数据源？使用 application.yml 配置 spring.datasource.url 等参数。";
        String expectedQaContent = "## Q1\n问题：如何配置 Spring Boot 的数据源？\n回答：使用 application.yml 配置 spring.datasource.url 等参数。";
        // 处理器会用 <content> 标签包裹 rawContent 后传给 LLM
        when(llmClient.complete(anyString(), argThat(arg -> arg.contains(rawContent) && arg.contains("<content>"))))
                .thenReturn(expectedQaContent);

        // 执行
        ProcessResult result = processor.process(rawContent, "FEISHU_CHAT", Map.of());

        // 验证
        assertNull(result.guideContent(), "FeishuQaProcessor 不应生成 Guide 内容");
        assertEquals(expectedQaContent, result.qaContent(), "qaContent 应为 LLM 返回的内容");
        assertEquals(PROCESSOR_VERSION, result.processorVersion(), "processorVersion 应匹配配置");
    }

    @Test
    void processorVersionMatchesConfig() {
        String rawContent = "测试内容";
        when(llmClient.complete(anyString(), argThat(arg -> arg.contains(rawContent) && arg.contains("<content>"))))
                .thenReturn("Q&A output");

        ProcessResult result = processor.process(rawContent, "FEISHU_CHAT", null);

        assertEquals(PROCESSOR_VERSION, result.processorVersion());
    }

    @Test
    void llmIsCalledWithCorrectSystemPromptAndWrappedUserPrompt() {
        String rawContent = "原始内容用于 Q&A 重写";
        when(llmClient.complete(anyString(), anyString())).thenReturn("qa result");

        processor.process(rawContent, "FEISHU_CHAT", Map.of());

        // 验证 LLM 被调用时，system prompt 包含模板关键内容，user prompt 用 <content> 标签包裹原始内容
        verify(llmClient, times(1)).complete(
                argThat(systemPrompt -> systemPrompt.contains("问答对") && systemPrompt.contains("Q&A")),
                argThat(userPrompt -> userPrompt.startsWith("<content>")
                        && userPrompt.endsWith("</content>")
                        && userPrompt.contains(rawContent)));
    }

    @Test
    void llmFailurePropagatesAsExternalServiceException() {
        String rawContent = "会导致 LLM 失败的内容";
        ExternalServiceException llmException = new ExternalServiceException(
                "LLM 调用失败", null, "LLM", 500);
        when(llmClient.complete(anyString(), argThat(arg -> arg.contains(rawContent) && arg.contains("<content>"))))
                .thenThrow(llmException);

        // 验证异常直接传播
        ExternalServiceException thrown = assertThrows(ExternalServiceException.class,
                () -> processor.process(rawContent, "FEISHU_CHAT", Map.of()));
        assertEquals("LLM", thrown.getServiceName());
    }
}
