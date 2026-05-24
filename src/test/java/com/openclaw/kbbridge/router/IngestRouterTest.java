package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.exception.BizException;
import com.openclaw.kbbridge.processor.KnowledgeProcessor;
import com.openclaw.kbbridge.processor.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IngestRouter 单元测试。
 * <p>
 * 验证不同 sourceType 路由到正确的处理器，以及未知类型的回退行为。
 * </p>
 */
class IngestRouterTest {

    /**
     * 模拟的 MarkdownKnowledgeProcessor
     */
    private final KnowledgeProcessor markdownProcessor = (raw, type, ctx) -> new ProcessResult("guide", "qa", "v1");

    /**
     * 模拟的 FeishuQaProcessor
     */
    private final KnowledgeProcessor feishuQaProcessor = (raw, type, ctx) -> new ProcessResult(null, "qa", "v1");

    /**
     * 模拟的 AttachmentProcessor
     */
    private final KnowledgeProcessor attachmentProcessor = (raw, type, ctx) -> new ProcessResult(null, "qa", "v1");

    private IngestRouter router;

    @BeforeEach
    void setUp() {
        Map<String, KnowledgeProcessor> processorMap = new HashMap<>();
        processorMap.put("markdownKnowledgeProcessor", markdownProcessor);
        processorMap.put("feishuQaProcessor", feishuQaProcessor);
        processorMap.put("attachmentProcessor", attachmentProcessor);
        router = new IngestRouter(processorMap);
    }

    @Test
    void markdownSourceTypeRoutesToMarkdownProcessor() {
        KnowledgeProcessor result = router.route("MARKDOWN");
        assertSame(markdownProcessor, result);
    }

    @Test
    void tutorialSourceTypeRoutesToMarkdownProcessor() {
        KnowledgeProcessor result = router.route("TUTORIAL");
        assertSame(markdownProcessor, result);
    }

    @Test
    void noteSourceTypeRoutesToMarkdownProcessor() {
        KnowledgeProcessor result = router.route("NOTE");
        assertSame(markdownProcessor, result);
    }

    @Test
    void feishuChatSourceTypeRoutesToFeishuQaProcessor() {
        KnowledgeProcessor result = router.route("FEISHU_CHAT");
        assertSame(feishuQaProcessor, result);
    }

    @Test
    void feishuChatWithShortQaContentRoutesToFeishuQaProcessor() {
        String content = """
                Q: How do I reset my password?
                A: Open settings and choose Reset Password.
                """;

        KnowledgeProcessor result = router.route("FEISHU_CHAT", content);

        assertSame(feishuQaProcessor, result);
    }

    @Test
    void feishuChatWithLongMarkdownDocumentRoutesToMarkdownProcessor() {
        String content = "# Deployment Guide\n\n"
                + "This section explains the deployment flow and operational checks.\n\n".repeat(20)
                + "## Rollback\n\n"
                + "Use the rollback command when the deployment health check fails.\n\n".repeat(20);

        KnowledgeProcessor result = router.route("FEISHU_CHAT", content);

        assertSame(markdownProcessor, result);
    }

    @Test
    void feishuChatWithLongStructuredDocumentRoutesToMarkdownProcessor() {
        String content = """
                Deployment Overview

                1. Prepare the runtime environment and verify required variables.

                2. Build the service package and publish the artifact.

                3. Start the service and inspect the health endpoint.

                Rollback Plan

                - Stop the new service instance.
                - Restore the previous artifact.
                - Re-run the health check.

                Verification

                Confirm logs, metrics, and user-facing APIs before closing the task.

                """.repeat(5);

        KnowledgeProcessor result = router.route("FEISHU_CHAT", content);

        assertSame(markdownProcessor, result);
    }

    @Test
    void attachmentSourceTypeRoutesToAttachmentProcessor() {
        KnowledgeProcessor result = router.route("ATTACHMENT");
        assertSame(attachmentProcessor, result);
    }

    @Test
    void unknownSourceTypeFallsBackToFeishuQaProcessor() {
        KnowledgeProcessor result = router.route("UNKNOWN_TYPE");
        assertSame(feishuQaProcessor, result);
    }

    @Test
    void nullSourceTypeFallsBackToFeishuQaProcessor() {
        KnowledgeProcessor result = router.route(null);
        assertSame(feishuQaProcessor, result);
    }

    @Test
    void sourceTypeIsCaseInsensitive() {
        assertSame(markdownProcessor, router.route("markdown"));
        assertSame(feishuQaProcessor, router.route("feishu_chat"));
        assertSame(attachmentProcessor, router.route("attachment"));
    }

    @Test
    void throwsBizExceptionWhenProcessorNotRegistered() {
        // 创建一个只有部分处理器的路由器
        Map<String, KnowledgeProcessor> partialMap = new HashMap<>();
        partialMap.put("feishuQaProcessor", feishuQaProcessor);
        IngestRouter partialRouter = new IngestRouter(partialMap);

        // MARKDOWN 类型需要 markdownKnowledgeProcessor，但未注册
        BizException ex = assertThrows(BizException.class,
                () -> partialRouter.route("MARKDOWN"));
        assertTrue(ex.getMessage().contains("markdownKnowledgeProcessor"));
    }

    @Test
    void throwsBizExceptionWhenNoProcessorsRegistered() {
        IngestRouter emptyRouter = new IngestRouter(Collections.emptyMap());

        BizException ex = assertThrows(BizException.class,
                () -> emptyRouter.route("FEISHU_CHAT"));
        assertTrue(ex.getMessage().contains("feishuQaProcessor"));
    }
}
