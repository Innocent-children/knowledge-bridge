package com.openclaw.kbbridge.preservation;

import com.openclaw.kbbridge.builder.EvidencePackBuilder;
import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.client.RagflowClient;
import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.*;
import com.openclaw.kbbridge.dto.ragflow.RetrievalRequest;
import com.openclaw.kbbridge.dto.ragflow.RetrievalResponse;
import com.openclaw.kbbridge.entity.QueryLogEntity;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.repository.QueryLogMapper;
import com.openclaw.kbbridge.router.LlmRouteStrategy;
import com.openclaw.kbbridge.router.QueryRouter;
import com.openclaw.kbbridge.router.RuleBasedRouteStrategy;
import com.openclaw.kbbridge.service.QueryService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Preservation Property Tests for Query Flow (Phase 2).
 * <p>
 * These tests verify existing correct behavior on UNFIXED code that must be
 * preserved after bug fixes are applied. They are EXPECTED TO PASS on unfixed
 * code.
 * </p>
 * <p>
 * Tests follow observation-first methodology: observe behavior on unfixed code
 * for non-buggy inputs (non-null, non-empty sources; valid query requests).
 * </p>
 */
class QueryFlowPreservationPropertyTest {

        // ── Preservation P5: EvidencePackBuilder sort/truncate/quality ──

        /**
         * Preservation P5: For any call to EvidencePackBuilder.build() with non-null,
         * non-empty sources, the result is sorted by score descending, truncated by
         * maxSources/maxContentLength/maxTotalLength, and RetrievalQuality is computed
         * correctly.
         * <p>
         * This verifies the core evidence pack building logic is preserved after fixes.
         * </p>
         *
         * <b>Validates: Requirements 3.5</b>
         */
        @Property(tries = 50)
        void evidencePackBuilderSortsTruncatesAndComputesQuality(
                        @ForAll("nonEmptySourceLists") List<EvidenceSource> sources,
                        @ForAll("kbRoutes") QueryRoute route) {

                KbProperties kbProperties = createKbProperties();
                KbMetrics kbMetrics = mock(KbMetrics.class);
                EvidencePackBuilder builder = new EvidencePackBuilder(kbProperties, kbMetrics);

                String requestId = "test-req-" + System.nanoTime();
                QueryResponse response = builder.build(requestId, route, sources);

                // Verify response is not null and has correct requestId/route
                assertNotNull(response, "Response should not be null");
                assertEquals(requestId, response.requestId(), "RequestId should match");
                assertEquals(route, response.route(), "Route should match");

                // Verify sources are sorted by score descending
                List<EvidenceSource> resultSources = response.sources();
                for (int i = 1; i < resultSources.size(); i++) {
                        assertTrue(resultSources.get(i - 1).score() >= resultSources.get(i).score(),
                                        "Sources should be sorted by score descending: " +
                                                        resultSources.get(i - 1).score() + " >= "
                                                        + resultSources.get(i).score());
                }

                // Verify maxSources truncation
                int maxSources = kbProperties.getQuery().getMaxSources();
                assertTrue(resultSources.size() <= maxSources,
                                "Result sources count (" + resultSources.size() +
                                                ") should not exceed maxSources (" + maxSources + ")");

                // Verify maxContentLength truncation per source
                int maxContentLength = kbProperties.getQuery().getMaxContentLength();
                for (EvidenceSource src : resultSources) {
                        if (src.content() != null) {
                                // Content may have "[...]" appended if truncated, so actual length can be
                                // maxContentLength + 5
                                assertTrue(src.content().length() <= maxContentLength + 5,
                                                "Each source content length should not exceed maxContentLength + truncation marker");
                        }
                }

                // Verify maxTotalLength truncation
                int maxTotalLength = kbProperties.getQuery().getMaxTotalLength();
                int totalLength = resultSources.stream()
                                .mapToInt(s -> s.content() == null ? 0 : s.content().length())
                                .sum();
                assertTrue(totalLength <= maxTotalLength,
                                "Total content length (" + totalLength +
                                                ") should not exceed maxTotalLength (" + maxTotalLength + ")");

                // Verify RetrievalQuality is computed correctly
                RetrievalQuality quality = response.retrievalQuality();
                assertNotNull(quality, "RetrievalQuality should not be null");
                assertEquals(resultSources.size(), quality.hitCount(),
                                "hitCount should equal the number of result sources");
                assertEquals(sources.size(), quality.originalHitCount(),
                                "originalHitCount should equal the original sources count");

                // Verify confidence is computed from top score
                double topScore = resultSources.isEmpty() ? 0.0 : resultSources.getFirst().score();
                Confidence expectedConfidence = Confidence.fromScore(topScore);
                assertEquals(expectedConfidence, quality.confidence(),
                                "Confidence should be computed from top score: " + topScore);

                // Verify truncated flag: if no truncation happened, it should be false
                if (sources.size() <= maxSources && !anyContentTruncated(sources, maxContentLength)
                                && totalContentLength(sources) <= maxTotalLength) {
                        assertFalse(quality.truncated(), "Truncated should be false when no truncation occurred");
                }
        }

        /**
         * Preservation P5 (continued): Verify allowModelSupplement is set correctly
         * based on route.
         *
         * <b>Validates: Requirements 3.5</b>
         */
        @Property(tries = 20)
        void evidencePackBuilderSetsAllowModelSupplementByRoute(
                        @ForAll("nonEmptySourceLists") List<EvidenceSource> sources,
                        @ForAll("kbRoutes") QueryRoute route) {

                KbProperties kbProperties = createKbProperties();
                KbMetrics kbMetrics = mock(KbMetrics.class);
                EvidencePackBuilder builder = new EvidencePackBuilder(kbProperties, kbMetrics);

                QueryResponse response = builder.build("req-test", route, sources);

                boolean expectedAllowSupplement = (route == QueryRoute.KB_PLUS_LLM);
                assertEquals(expectedAllowSupplement, response.allowModelSupplement(),
                                "allowModelSupplement should be true only for KB_PLUS_LLM route");
        }

        // ── Preservation P6: LLM_ONLY query returns empty evidence pack ──

        /**
         * Preservation P6: For any LLM_ONLY query, QueryService returns an empty
         * evidence pack without calling RAGFlow retrieval.
         * <p>
         * This verifies the LLM_ONLY short-circuit path is preserved.
         * </p>
         *
         * <b>Validates: Requirements 3.8</b>
         */
        @Property(tries = 30)
        void llmOnlyQueryReturnsEmptyPackWithoutRagflowCall(
                        @ForAll("requestIds") String requestId,
                        @ForAll("questions") String question,
                        @ForAll("userIds") String userId) {

                // Setup mocks
                QueryRouter queryRouter = mock(QueryRouter.class);
                RagflowClient ragflowClient = mock(RagflowClient.class);
                KbProperties kbProperties = createKbProperties();
                KbMetrics kbMetrics = mock(KbMetrics.class);
                EvidencePackBuilder evidencePackBuilder = new EvidencePackBuilder(kbProperties, kbMetrics);
                QueryLogMapper queryLogMapper = mock(QueryLogMapper.class);
                ObjectMapper objectMapper = new ObjectMapper();

                // Route to LLM_ONLY
                when(queryRouter.route(any(QueryRequest.class))).thenReturn(QueryRoute.LLM_ONLY);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                QueryService queryService = new QueryService(
                                queryRouter, ragflowClient, evidencePackBuilder,
                                queryLogMapper, kbProperties, objectMapper, kbMetrics);

                QueryRequest request = new QueryRequest(
                                requestId, userId, null, null, null,
                                question, null, false, null);

                QueryResponse response = queryService.query(request);

                // Verify empty evidence pack
                assertNotNull(response, "Response should not be null");
                assertEquals(requestId, response.requestId(), "RequestId should match");
                assertEquals(QueryRoute.LLM_ONLY, response.route(), "Route should be LLM_ONLY");
                assertTrue(response.sources().isEmpty(), "Sources should be empty for LLM_ONLY");
                assertFalse(response.allowModelSupplement(),
                                "allowModelSupplement should be false for LLM_ONLY");

                // Verify RetrievalQuality
                RetrievalQuality quality = response.retrievalQuality();
                assertEquals(0, quality.hitCount(), "hitCount should be 0");
                assertEquals(Confidence.LOW, quality.confidence(), "Confidence should be LOW");
                assertFalse(quality.truncated(), "Should not be truncated");
                assertEquals(0, quality.originalHitCount(), "originalHitCount should be 0");

                // Verify RAGFlow was NOT called
                verify(ragflowClient, never()).retrieval(any(), any());

                // Verify query log was saved
                verify(queryLogMapper).insert(any(QueryLogEntity.class));
                verify(queryLogMapper).updateById(any(QueryLogEntity.class));
        }

        // ── Preservation P7: KB route query converts, dedups, builds, saves ──

        /**
         * Preservation P7: For any query with KB route and RAGFlow results,
         * QueryService converts, dedups, builds evidence pack, and saves result
         * to query log.
         * <p>
         * This verifies the full KB query flow is preserved.
         * </p>
         *
         * <b>Validates: Requirements 3.9</b>
         */
        @Property(tries = 30)
        void kbRouteQueryConvertsDedupsBuildsAndSaves(
                        @ForAll("requestIds") String requestId,
                        @ForAll("questions") String question,
                        @ForAll("userIds") String userId,
                        @ForAll("kbRoutes") QueryRoute route,
                        @ForAll("ragflowChunkLists") List<RetrievalResponse.Chunk> chunks) {

                // Skip LLM_ONLY since that's covered by P6
                Assume.that(route != QueryRoute.LLM_ONLY);

                // Setup mocks
                QueryRouter queryRouter = mock(QueryRouter.class);
                RagflowClient ragflowClient = mock(RagflowClient.class);
                KbProperties kbProperties = createKbProperties();
                // Disable memory search to keep test focused
                kbProperties.getQuery().setMemoryEnabled(false);
                KbMetrics kbMetrics = mock(KbMetrics.class);
                EvidencePackBuilder evidencePackBuilder = new EvidencePackBuilder(kbProperties, kbMetrics);
                QueryLogMapper queryLogMapper = mock(QueryLogMapper.class);
                ObjectMapper objectMapper = new ObjectMapper();

                when(queryRouter.route(any(QueryRequest.class))).thenReturn(route);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                // RAGFlow returns the generated chunks
                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(chunks);
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq(requestId)))
                                .thenReturn(retrievalResponse);

                QueryService queryService = new QueryService(
                                queryRouter, ragflowClient, evidencePackBuilder,
                                queryLogMapper, kbProperties, objectMapper, kbMetrics);

                QueryRequest request = new QueryRequest(
                                requestId, userId, null, null, null,
                                question, null, false, null);

                QueryResponse response = queryService.query(request);

                // Verify response structure
                assertNotNull(response, "Response should not be null");
                assertEquals(requestId, response.requestId(), "RequestId should match");
                assertEquals(route, response.route(), "Route should match");

                // Verify RAGFlow was called
                verify(ragflowClient).retrieval(any(RetrievalRequest.class), eq(requestId));

                // Verify deduplication: no duplicate content in result
                List<String> contents = response.sources().stream()
                                .map(EvidenceSource::content)
                                .toList();
                long uniqueContents = contents.stream().distinct().count();
                assertEquals(uniqueContents, contents.size(),
                                "Result sources should not contain duplicate content");

                // Verify sources are sorted by score descending
                for (int i = 1; i < response.sources().size(); i++) {
                        assertTrue(response.sources().get(i - 1).score() >= response.sources().get(i).score(),
                                        "Sources should be sorted by score descending");
                }

                // Verify truncation limits
                assertTrue(response.sources().size() <= kbProperties.getQuery().getMaxSources(),
                                "Sources count should not exceed maxSources");

                // Verify RetrievalQuality is present
                assertNotNull(response.retrievalQuality(), "RetrievalQuality should not be null");

                // Verify query log was saved (insert + updateById)
                verify(queryLogMapper).insert(any(QueryLogEntity.class));
                ArgumentCaptor<QueryLogEntity> logCaptor = ArgumentCaptor.forClass(QueryLogEntity.class);
                verify(queryLogMapper).updateById(logCaptor.capture());
                QueryLogEntity savedLog = logCaptor.getValue();

                // Verify log has route set
                assertEquals(route.name(), savedLog.getRoute(), "Query log route should be set");

                // Verify log has source count
                assertNotNull(savedLog.getSourceCount(), "Query log sourceCount should be set");
                assertEquals(response.sources().size(), savedLog.getSourceCount(),
                                "Query log sourceCount should match response sources count");

                // Verify log has retrieval quality
                assertNotNull(savedLog.getRetrievalQuality(), "Query log retrievalQuality should be set");
        }

        // ── Preservation P8: LlmRouteStrategy flag-based routing ──

        /**
         * Preservation P8: For any LlmRouteStrategy request with
         * flags.strictKbOnly=true,
         * returns KB_ONLY without calling LLM.
         * <p>
         * This verifies the flag-based routing shortcut in LlmRouteStrategy is
         * preserved.
         * </p>
         *
         * <b>Validates: Requirements 3.13</b>
         */
        @Property(tries = 30)
        void llmRouteStrategyStrictKbOnlyReturnsKbOnlyWithoutLlm(
                        @ForAll("requestIds") String requestId,
                        @ForAll("questions") String question,
                        @ForAll("userIds") String userId) {

                LlmClient llmClient = mock(LlmClient.class);
                KbProperties.Query queryConfig = createKbProperties().getQuery();
                RuleBasedRouteStrategy fallback = new RuleBasedRouteStrategy(queryConfig);

                LlmRouteStrategy strategy = new LlmRouteStrategy(llmClient, queryConfig, fallback, new ObjectMapper());

                // strictKbOnly=true
                QueryFlags flags = new QueryFlags(true, false);
                QueryRequest request = new QueryRequest(
                                requestId, userId, null, null, null,
                                question, null, false, flags);

                QueryRoute result = strategy.resolve(request);

                assertEquals(QueryRoute.KB_ONLY, result,
                                "strictKbOnly=true should return KB_ONLY");

                // Verify LLM was NOT called
                verify(llmClient, never()).complete(any(), any());
                verify(llmClient, never()).complete(any(), any(), any());
        }

        /**
         * Preservation P8 (continued): question containing #kb returns KB_ONLY
         * without calling LLM.
         *
         * <b>Validates: Requirements 3.13</b>
         */
        @Property(tries = 30)
        void llmRouteStrategyKbTagReturnsKbOnlyWithoutLlm(
                        @ForAll("requestIds") String requestId,
                        @ForAll("userIds") String userId) {

                LlmClient llmClient = mock(LlmClient.class);
                KbProperties.Query queryConfig = createKbProperties().getQuery();
                RuleBasedRouteStrategy fallback = new RuleBasedRouteStrategy(queryConfig);

                LlmRouteStrategy strategy = new LlmRouteStrategy(llmClient, queryConfig, fallback, new ObjectMapper());

                QueryRequest request = new QueryRequest(
                                requestId, userId, null, null, null,
                                "#kb 测试问题", null, false, null);

                QueryRoute result = strategy.resolve(request);

                assertEquals(QueryRoute.KB_ONLY, result,
                                "#kb tag should return KB_ONLY");

                // Verify LLM was NOT called
                verify(llmClient, never()).complete(any(), any());
                verify(llmClient, never()).complete(any(), any(), any());
        }

        /**
         * Preservation P8 (continued): #kb tag takes precedence over strictKbOnly
         * (both map to KB_ONLY).
         *
         * <b>Validates: Requirements 3.13</b>
         */
        @Example
        void kbTagAndStrictKbOnlyBothReturnKbOnly() {
                LlmClient llmClient = mock(LlmClient.class);
                KbProperties.Query queryConfig = createKbProperties().getQuery();
                RuleBasedRouteStrategy fallback = new RuleBasedRouteStrategy(queryConfig);

                LlmRouteStrategy strategy = new LlmRouteStrategy(llmClient, queryConfig, fallback, new ObjectMapper());

                // Both #kb tag and strictKbOnly flag
                QueryFlags flags = new QueryFlags(true, false);
                QueryRequest request = new QueryRequest(
                                "req-precedence", "user1", null, null, null,
                                "#kb test question", null, false, flags);

                QueryRoute result = strategy.resolve(request);

                assertEquals(QueryRoute.KB_ONLY, result,
                                "#kb tag and strictKbOnly should both return KB_ONLY");
                verify(llmClient, never()).complete(any(), any());
                verify(llmClient, never()).complete(any(), any(), any());
        }

        // ── Providers ──

        @Provide
        Arbitrary<String> requestIds() {
                return Arbitraries.strings()
                                .alpha()
                                .ofMinLength(5)
                                .ofMaxLength(20)
                                .map(s -> "req-" + s);
        }

        @Provide
        Arbitrary<String> questions() {
                return Arbitraries.of(
                                "如何配置知识库？",
                                "什么是 RAGFlow？",
                                "帮我查一下文档",
                                "系统架构是什么？",
                                "How to use the API?",
                                "Tell me about the project");
        }

        @Provide
        Arbitrary<String> userIds() {
                return Arbitraries.strings()
                                .alpha()
                                .ofMinLength(3)
                                .ofMaxLength(10)
                                .map(s -> "user-" + s);
        }

        @Provide
        Arbitrary<QueryRoute> kbRoutes() {
                return Arbitraries.of(QueryRoute.values());
        }

        @Provide
        Arbitrary<List<EvidenceSource>> nonEmptySourceLists() {
                return evidenceSources()
                                .list()
                                .ofMinSize(1)
                                .ofMaxSize(10);
        }

        @Provide
        Arbitrary<List<RetrievalResponse.Chunk>> ragflowChunkLists() {
                return ragflowChunks()
                                .list()
                                .ofMinSize(1)
                                .ofMaxSize(8);
        }

        private Arbitrary<EvidenceSource> evidenceSources() {
                Arbitrary<String> datasets = Arbitraries.of("kb_qa", "kb_guide", "memory");
                Arbitrary<String> titles = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(30)
                                .map(s -> "Doc-" + s);
                Arbitrary<String> contents = Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(500)
                                .map(s -> "Content: " + s);
                Arbitrary<Double> scores = Arbitraries.doubles().between(0.0, 1.0);

                return Combinators.combine(datasets, titles, contents, scores)
                                .as((dataset, title, content, score) -> new EvidenceSource(dataset, title, content,
                                                score, Map.of()));
        }

        private Arbitrary<RetrievalResponse.Chunk> ragflowChunks() {
                Arbitrary<String> contents = Arbitraries.strings().alpha().ofMinLength(10).ofMaxLength(300)
                                .map(s -> "Chunk: " + s);
                Arbitrary<Double> scores = Arbitraries.doubles().between(0.1, 1.0);
                Arbitrary<String> docNames = Arbitraries.of("doc1.md", "doc2.md", "guide.md", "faq.md");
                Arbitrary<String> datasetIds = Arbitraries.of("ds-001", "ds-002", "ds-003");

                return Combinators.combine(contents, scores, docNames, datasetIds)
                                .as((content, score, docName, datasetId) -> new RetrievalResponse.Chunk(content, score,
                                                docName,
                                                datasetId, null));
        }

        // ── Helper Methods ──

        private KbProperties createKbProperties() {
                KbProperties props = new KbProperties();

                KbProperties.Ragflow ragflow = new KbProperties.Ragflow();
                ragflow.setBaseUrl("http://localhost:9380");
                ragflow.setApiKey("test-api-key");
                props.setRagflow(ragflow);

                KbProperties.Query query = new KbProperties.Query();
                query.setMaxSources(5);
                query.setMaxContentLength(2000);
                query.setMaxTotalLength(8000);
                query.setScoreThreshold(0.6);
                query.setRouteStrategy("rule");
                query.setDefaultRoute("KB_PLUS_LLM");
                query.setMemoryEnabled(false);
                props.setQuery(query);

                KbProperties.Minio minio = new KbProperties.Minio();
                minio.setRawBucket("kb-raw");
                minio.setProcessedBucket("kb-processed");
                props.setMinio(minio);

                KbProperties.Ingest ingest = new KbProperties.Ingest();
                ingest.setAsyncPoolSize(4);
                props.setIngest(ingest);

                return props;
        }

        private int totalContentLength(List<EvidenceSource> sources) {
                return sources.stream()
                                .mapToInt(s -> s.content() == null ? 0 : s.content().length())
                                .sum();
        }

        private boolean anyContentTruncated(List<EvidenceSource> sources, int maxContentLength) {
                return sources.stream()
                                .anyMatch(s -> s.content() != null && s.content().length() > maxContentLength);
        }
}
