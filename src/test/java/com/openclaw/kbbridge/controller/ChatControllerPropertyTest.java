package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.dto.chat.ChatRequest;
import com.openclaw.kbbridge.dto.chat.ChatResponse;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.dto.query.RetrievalQuality;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.service.QueryService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ChatController 上下文构建属性测试。
 * <p>
 * 使用 jqwik 属性测试框架验证 ChatController 根据 QueryResponse 的 sources 和 route
 * 正确选择包含证据上下文或默认系统提示词调用 LlmClient。
 * </p>
 * <p>
 * Feature: web-management-console, Property 3: Chat Endpoint Context
 * Construction
 * Validates: Requirements 5.3, 5.4
 */
@Tag("Feature: web-management-console, Property 3: Chat Endpoint Context Construction")
class ChatControllerPropertyTest {

        /**
         * 当 sources 非空且 route 不是 LLM_ONLY 时，
         * LlmClient.complete() 应被调用，且系统提示词包含每条证据的 content。
         * <p>
         * **Validates: Requirements 5.3**
         */
        @Property(tries = 100)
        void llmCalledWithEvidencePrompt_whenSourcesNonEmptyAndRouteNotLlmOnly(
                        @ForAll("nonEmptySourcesWithNonLlmOnlyRoute") QueryResponse queryResponse) {

                // Arrange
                QueryService queryService = mock(QueryService.class);
                LlmClient llmClient = mock(LlmClient.class);
                when(queryService.query(any())).thenReturn(queryResponse);
                when(llmClient.complete(any(), any())).thenReturn("test answer");

                ChatController controller = new ChatController(queryService, llmClient);
                ChatRequest request = new ChatRequest("test question");

                // Act
                ResponseEntity<ChatResponse> response = controller.chat(request);

                // Assert — LlmClient.complete() was called
                ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
                verify(llmClient).complete(systemPromptCaptor.capture(), any());

                String systemPrompt = systemPromptCaptor.getValue();

                // The system prompt must contain the content of every evidence source
                for (EvidenceSource source : queryResponse.sources()) {
                        assertTrue(systemPrompt.contains(source.content()),
                                        "System prompt should contain evidence content: '" + source.content()
                                                        + "' but was: " + systemPrompt);
                }

                // The system prompt should contain the "知识来源" marker
                assertTrue(systemPrompt.contains("知识来源"),
                                "System prompt should contain '知识来源' section header");
        }

        /**
         * 当 sources 为空或 route 是 LLM_ONLY 时，
         * LlmClient.complete() 应被调用，且系统提示词不包含证据内容。
         * <p>
         * **Validates: Requirements 5.4**
         */
        @Property(tries = 100)
        void llmCalledWithDefaultPrompt_whenSourcesEmptyOrRouteLlmOnly(
                        @ForAll("emptySourcesOrLlmOnlyRoute") QueryResponse queryResponse) {

                // Arrange
                QueryService queryService = mock(QueryService.class);
                LlmClient llmClient = mock(LlmClient.class);
                when(queryService.query(any())).thenReturn(queryResponse);
                when(llmClient.complete(any(), any())).thenReturn("test answer");

                ChatController controller = new ChatController(queryService, llmClient);
                ChatRequest request = new ChatRequest("test question");

                // Act
                ResponseEntity<ChatResponse> response = controller.chat(request);

                // Assert — LlmClient.complete() was called
                ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
                verify(llmClient).complete(systemPromptCaptor.capture(), any());

                String systemPrompt = systemPromptCaptor.getValue();

                // The system prompt should NOT contain evidence section markers
                assertFalse(systemPrompt.contains("知识来源"),
                                "System prompt should NOT contain '知识来源' when sources are empty or route is LLM_ONLY");

                // If the queryResponse has sources, verify none of their content appears in the
                // prompt
                if (queryResponse.sources() != null) {
                        for (EvidenceSource source : queryResponse.sources()) {
                                if (source.content() != null && !source.content().isBlank()) {
                                        assertFalse(systemPrompt.contains(source.content()),
                                                        "System prompt should NOT contain evidence content '"
                                                                        + source.content()
                                                                        + "' when route is LLM_ONLY");
                                }
                        }
                }
        }

        // ========== Custom Arbitrary Providers ==========

        /**
         * Generates QueryResponse objects with non-empty sources and route != LLM_ONLY.
         * This represents the case where evidence context should be included in the
         * system prompt.
         */
        @Provide
        Arbitrary<QueryResponse> nonEmptySourcesWithNonLlmOnlyRoute() {
                Arbitrary<List<EvidenceSource>> sourcesArb = evidenceSources()
                                .list().ofMinSize(1).ofMaxSize(5);
                Arbitrary<QueryRoute> routeArb = Arbitraries.of(
                                QueryRoute.KB_ONLY, QueryRoute.KB_PLUS_LLM);

                return Combinators.combine(sourcesArb, routeArb).as((sources, route) -> {
                        RetrievalQuality quality = new RetrievalQuality(
                                        sources.size(), Confidence.MEDIUM, false, sources.size());
                        return new QueryResponse(
                                        "req-" + java.util.UUID.randomUUID(),
                                        route,
                                        route == QueryRoute.KB_PLUS_LLM,
                                        sources,
                                        List.of("instruction"),
                                        quality);
                });
        }

        /**
         * Generates QueryResponse objects where either sources are empty OR route is
         * LLM_ONLY.
         * This represents the case where the default system prompt (no evidence) should
         * be used.
         */
        @Provide
        Arbitrary<QueryResponse> emptySourcesOrLlmOnlyRoute() {
                // Case 1: Empty sources with any route
                Arbitrary<QueryResponse> emptySourcesArb = Arbitraries.of(QueryRoute.values())
                                .map(route -> new QueryResponse(
                                                "req-" + java.util.UUID.randomUUID(),
                                                route,
                                                false,
                                                List.of(),
                                                List.of("instruction"),
                                                new RetrievalQuality(0, Confidence.LOW, false, 0)));

                // Case 2: LLM_ONLY route with any sources (including non-empty)
                Arbitrary<QueryResponse> llmOnlyArb = evidenceSources()
                                .list().ofMinSize(0).ofMaxSize(5)
                                .map(sources -> new QueryResponse(
                                                "req-" + java.util.UUID.randomUUID(),
                                                QueryRoute.LLM_ONLY,
                                                false,
                                                sources,
                                                List.of("instruction"),
                                                new RetrievalQuality(sources.size(), Confidence.LOW, false,
                                                                sources.size())));

                return Arbitraries.oneOf(emptySourcesArb, llmOnlyArb);
        }

        /**
         * Generates a single EvidenceSource with random but meaningful data.
         * Content is constrained to alphanumeric strings to avoid issues with special
         * characters
         * in prompt matching assertions.
         */
        private Arbitrary<EvidenceSource> evidenceSources() {
                Arbitrary<String> datasetArb = Arbitraries.of("kb_qa", "kb_guide", "kb_policy", "memory");
                Arbitrary<String> titleArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30);
                Arbitrary<String> contentArb = Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(100);
                Arbitrary<Double> scoreArb = Arbitraries.doubles().between(0.0, 1.0);

                return Combinators.combine(datasetArb, titleArb, contentArb, scoreArb)
                                .as((dataset, title, content, score) -> new EvidenceSource(dataset, title, content,
                                                score, Map.of()));
        }
}
