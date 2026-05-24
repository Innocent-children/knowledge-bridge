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
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatController 响应完整性属性测试。
 * <p>
 * 使用 jqwik 属性测试框架验证：当 QueryService 和 LlmClient 均成功时，
 * ChatResponse 始终包含非空 answer、非空 route、非空 sources 列表，且 llmError 为 false。
 * </p>
 * <p>
 * Feature: web-management-console, Property 4: Chat Response Completeness
 * Validates: Requirements 5.5
 */
@Tag("Feature: web-management-console, Property 4: Chat Response Completeness")
class ChatControllerResponsePropertyTest {

    /**
     * 当 QueryService 和 LlmClient 均成功返回时，
     * ChatResponse 应包含非空 answer、非空 route、非空 sources 列表，且 llmError 为 false。
     * <p>
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 100)
    void chatResponse_alwaysComplete_whenBothServicesSucceed(
            @ForAll("validQuestions") String question,
            @ForAll("successfulQueryResponses") QueryResponse queryResponse,
            @ForAll("validLlmAnswers") String llmAnswer) {

        // Arrange
        QueryService queryService = mock(QueryService.class);
        LlmClient llmClient = mock(LlmClient.class);
        when(queryService.query(any())).thenReturn(queryResponse);
        when(llmClient.complete(any(), any())).thenReturn(llmAnswer);

        ChatController controller = new ChatController(queryService, llmClient);
        ChatRequest request = new ChatRequest(question);

        // Act
        ResponseEntity<ChatResponse> responseEntity = controller.chat(request);
        ChatResponse response = responseEntity.getBody();

        // Assert — response body is present
        assertNotNull(response, "ChatResponse body should not be null");

        // Assert — answer is non-null
        assertNotNull(response.answer(),
                "ChatResponse.answer should not be null when both services succeed");

        // Assert — route is non-null
        assertNotNull(response.route(),
                "ChatResponse.route should not be null when both services succeed");

        // Assert — sources is non-null (may be empty list, but never null)
        assertNotNull(response.sources(),
                "ChatResponse.sources should not be null when both services succeed");

        // Assert — llmError is false
        assertFalse(response.llmError(),
                "ChatResponse.llmError should be false when both services succeed");
    }

    // ========== Custom Arbitrary Providers ==========

    /**
     * Generates random valid question strings (non-blank).
     */
    @Provide
    Arbitrary<String> validQuestions() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(200)
                .map(s -> s + "?");
    }

    /**
     * Generates random successful QueryResponse objects with all valid route types
     * and varying source lists (including empty).
     */
    @Provide
    Arbitrary<QueryResponse> successfulQueryResponses() {
        Arbitrary<QueryRoute> routeArb = Arbitraries.of(QueryRoute.values());
        Arbitrary<List<EvidenceSource>> sourcesArb = evidenceSource()
                .list().ofMinSize(0).ofMaxSize(5);
        Arbitrary<Confidence> confidenceArb = Arbitraries.of(Confidence.values());

        return Combinators.combine(routeArb, sourcesArb, confidenceArb)
                .as((route, sources, confidence) -> {
                    RetrievalQuality quality = new RetrievalQuality(
                            sources.size(), confidence, false, sources.size());
                    return new QueryResponse(
                            "req-" + UUID.randomUUID(),
                            route,
                            route == QueryRoute.KB_PLUS_LLM,
                            sources,
                            List.of("instruction"),
                            quality);
                });
    }

    /**
     * Generates random non-null, non-empty LLM answer strings.
     */
    @Provide
    Arbitrary<String> validLlmAnswers() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(5)
                .ofMaxLength(500);
    }

    /**
     * Generates a single EvidenceSource with random but meaningful data.
     */
    private Arbitrary<EvidenceSource> evidenceSource() {
        Arbitrary<String> datasetArb = Arbitraries.of("kb_qa", "kb_guide", "kb_policy", "memory");
        Arbitrary<String> titleArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30);
        Arbitrary<String> contentArb = Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(100);
        Arbitrary<Double> scoreArb = Arbitraries.doubles().between(0.0, 1.0);

        return Combinators.combine(datasetArb, titleArb, contentArb, scoreArb)
                .as((dataset, title, content, score) ->
                        new EvidenceSource(dataset, title, content, score, Map.of()));
    }
}
