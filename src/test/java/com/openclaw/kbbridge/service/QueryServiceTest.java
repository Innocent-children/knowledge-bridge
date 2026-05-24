package com.openclaw.kbbridge.service;

import com.openclaw.kbbridge.builder.EvidencePackBuilder;
import com.openclaw.kbbridge.client.RagflowClient;
import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.dto.query.RetrievalQuality;
import com.openclaw.kbbridge.dto.ragflow.MemorySearchRequest;
import com.openclaw.kbbridge.dto.ragflow.MemorySearchResponse;
import com.openclaw.kbbridge.dto.ragflow.RetrievalRequest;
import com.openclaw.kbbridge.dto.ragflow.RetrievalResponse;
import com.openclaw.kbbridge.entity.QueryLogEntity;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.model.enums.QueryStatus;
import com.openclaw.kbbridge.repository.QueryLogMapper;
import com.openclaw.kbbridge.router.QueryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QueryService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

        @Mock
        private QueryRouter queryRouter;
        @Mock
        private RagflowClient ragflowClient;
        @Mock
        private EvidencePackBuilder evidencePackBuilder;
        @Mock
        private QueryLogMapper queryLogMapper;
        @Mock
        private KbMetrics kbMetrics;

        private KbProperties kbProperties;
        private ObjectMapper objectMapper;
        private QueryService queryService;

        @BeforeEach
        void setUp() {
                kbProperties = new KbProperties();
                kbProperties.getQuery().setMaxSources(5);
                kbProperties.getQuery().setScoreThreshold(0.6);
                kbProperties.getQuery().setMaxContentLength(2000);
                kbProperties.getQuery().setMaxTotalLength(8000);

                objectMapper = new ObjectMapper();
                queryService = new QueryService(
                                queryRouter, ragflowClient, evidencePackBuilder,
                                queryLogMapper, kbProperties, objectMapper, kbMetrics);
        }

        private QueryRequest createRequest(String requestId, String question) {
                return new QueryRequest(requestId, "user-1", "chat-1", "session-1",
                                "msg-1", question, "feishu", false, null);
        }

        // ── LLM_ONLY 路由测试 ──

        @Test
        @DisplayName("LLM_ONLY 路由返回空证据包，不调用 RAGFlow")
        void query_llmOnly_returnsEmptyPackWithoutCallingRagflow() {
                QueryRequest request = createRequest("req-1", "你好");
                when(queryRouter.route(request)).thenReturn(QueryRoute.LLM_ONLY);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                QueryResponse response = queryService.query(request);

                assertEquals("req-1", response.requestId());
                assertEquals(QueryRoute.LLM_ONLY, response.route());
                assertTrue(response.sources().isEmpty());
                assertFalse(response.allowModelSupplement());
                verifyNoInteractions(ragflowClient);
                verifyNoInteractions(evidencePackBuilder);
        }

        // ── KB_PLUS_LLM 正常检索测试 ──

        @Test
        @DisplayName("KB_PLUS_LLM 路由调用 RAGFlow 并返回证据包")
        void query_kbPlusLlm_callsRagflowAndReturnsEvidencePack() {
                QueryRequest request = createRequest("req-2", "如何部署");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_PLUS_LLM);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("部署步骤如下...", 0.85, "deploy-guide", "ds-1", null),
                                new RetrievalResponse.Chunk("配置说明...", 0.72, "config-doc", "ds-1", null)));
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-2")))
                                .thenReturn(retrievalResponse);

                List<EvidenceSource> sources = List.of(
                                new EvidenceSource("ds-1", "deploy-guide", "部署步骤如下...", 0.85, Map.of()),
                                new EvidenceSource("ds-1", "config-doc", "配置说明...", 0.72, Map.of()));
                QueryResponse expectedResponse = new QueryResponse("req-2", QueryRoute.KB_PLUS_LLM, true,
                                sources, List.of("优先依据 sources 回答"),
                                new RetrievalQuality(2, Confidence.HIGH, false, 2));
                when(evidencePackBuilder.build(eq("req-2"), eq(QueryRoute.KB_PLUS_LLM), any()))
                                .thenReturn(expectedResponse);

                QueryResponse response = queryService.query(request);

                assertEquals("req-2", response.requestId());
                assertEquals(QueryRoute.KB_PLUS_LLM, response.route());
                assertEquals(2, response.sources().size());
                verify(ragflowClient).retrieval(any(RetrievalRequest.class), eq("req-2"));
                verify(evidencePackBuilder).build(eq("req-2"), eq(QueryRoute.KB_PLUS_LLM), any());
        }

        // ── RAGFlow 异常降级测试：KB_ONLY ──

        @Test
        @DisplayName("RAGFlow 异常时 KB_ONLY 降级返回空 sources + '知识库暂无可靠答案'")
        void query_kbOnly_ragflowFailure_degradesWithEmptySourcesAndDeclaration() {
                QueryRequest request = createRequest("req-3", "#kb 问题");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_ONLY);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-3")))
                                .thenThrow(new ExternalServiceException("RAGFlow timeout", "req-3", "RAGFlow", 504));

                QueryResponse response = queryService.query(request);

                assertEquals("req-3", response.requestId());
                assertEquals(QueryRoute.KB_ONLY, response.route());
                assertTrue(response.sources().isEmpty());
                assertFalse(response.allowModelSupplement());
                assertTrue(response.instructions().contains("知识库暂无可靠答案"));
                assertEquals(Confidence.LOW, response.retrievalQuality().confidence());
        }

        // ── RAGFlow 异常降级测试：KB_PLUS_LLM ──

        @Test
        @DisplayName("RAGFlow 异常时 KB_PLUS_LLM 降级返回 LOW 置信度 + allowModelSupplement=true")
        void query_kbPlusLlm_ragflowFailure_degradesWithLowConfidenceAndModelSupplement() {
                QueryRequest request = createRequest("req-5", "问题");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_PLUS_LLM);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-5")))
                                .thenThrow(new ExternalServiceException("RAGFlow error", "req-5", "RAGFlow", 500));

                QueryResponse response = queryService.query(request);

                assertEquals("req-5", response.requestId());
                assertEquals(QueryRoute.KB_PLUS_LLM, response.route());
                assertTrue(response.sources().isEmpty());
                assertTrue(response.allowModelSupplement());
                assertEquals(Confidence.LOW, response.retrievalQuality().confidence());
        }

        // ── 查询日志状态转换测试 ──

        @Test
        @DisplayName("查询日志正确记录状态转换：QUERY_RECEIVED → ROUTE_* → ANSWERED")
        void query_persistsCorrectStatusTransitions() {
                QueryRequest request = createRequest("req-6", "测试");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_PLUS_LLM);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("内容", 0.9, "doc", "ds-1", null)));
                when(ragflowClient.retrieval(any(), eq("req-6"))).thenReturn(retrievalResponse);

                QueryResponse mockResponse = new QueryResponse("req-6", QueryRoute.KB_PLUS_LLM, true,
                                List.of(new EvidenceSource("ds-1", "doc", "内容", 0.9, Map.of())),
                                List.of("优先依据 sources 回答"),
                                new RetrievalQuality(1, Confidence.HIGH, false, 1));
                when(evidencePackBuilder.build(any(), any(), any())).thenReturn(mockResponse);

                doAnswer(invocation -> {
                        QueryLogEntity entity = invocation.getArgument(0);
                        assertEquals(QueryStatus.QUERY_RECEIVED.name(), entity.getStatus());
                        return 1;
                }).when(queryLogMapper).insert(any(QueryLogEntity.class));

                queryService.query(request);

                verify(queryLogMapper).insert(any(QueryLogEntity.class));
                ArgumentCaptor<QueryLogEntity> updateCaptor = ArgumentCaptor.forClass(QueryLogEntity.class);
                verify(queryLogMapper).updateById(updateCaptor.capture());
                QueryLogEntity updated = updateCaptor.getValue();
                assertEquals(QueryStatus.ANSWERED.name(), updated.getStatus());
                assertEquals("KB_PLUS_LLM", updated.getRoute());
        }

        @Test
        @DisplayName("不可恢复异常时查询日志状态为 ANSWER_FAILED")
        void query_unexpectedException_statusIsAnswerFailed() {
                QueryRequest request = createRequest("req-7", "问题");
                when(queryRouter.route(request)).thenThrow(new RuntimeException("unexpected"));
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                assertThrows(RuntimeException.class, () -> queryService.query(request));

                ArgumentCaptor<QueryLogEntity> captor = ArgumentCaptor.forClass(QueryLogEntity.class);
                verify(queryLogMapper).updateById(captor.capture());
                assertEquals(QueryStatus.ANSWER_FAILED.name(), captor.getValue().getStatus());
        }

        // ── routeToStatus 映射测试 ──

        @Test
        @DisplayName("QueryRoute 到 QueryStatus 映射正确")
        void routeToStatus_mapsCorrectly() {
                assertEquals(QueryStatus.ROUTE_LLM_ONLY, queryService.routeToStatus(QueryRoute.LLM_ONLY));
                assertEquals(QueryStatus.ROUTE_KB_ONLY, queryService.routeToStatus(QueryRoute.KB_ONLY));
                assertEquals(QueryStatus.ROUTE_KB_PLUS_LLM, queryService.routeToStatus(QueryRoute.KB_PLUS_LLM));
        }

        // ── 去重测试 ──

        @Test
        @DisplayName("去重移除重复 content 的 chunks")
        void convertAndDedup_removesDuplicateChunks() {
                RetrievalResponse response = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("相同内容", 0.9, "doc-1", "ds-1", null),
                                new RetrievalResponse.Chunk("相同内容", 0.8, "doc-2", "ds-1", null),
                                new RetrievalResponse.Chunk("不同内容", 0.7, "doc-3", "ds-1", null)));

                List<EvidenceSource> result = queryService.convertAndDedup(response);
                assertEquals(2, result.size());
        }

        @Test
        void convertAndDedup_handlesEmptyChunks() {
                assertTrue(queryService.convertAndDedup(RetrievalResponse.ofChunks(List.of())).isEmpty());
        }

        @Test
        void convertAndDedup_handlesNullResponse() {
                assertTrue(queryService.convertAndDedup(null).isEmpty());
        }

        // ── Memory 检索集成测试 ──

        @Test
        @DisplayName("KB_ONLY + memoryEnabled=true 时调用 Memory 检索并合并结果")
        void query_kbOnly_memoryEnabled_callsSearchMemoryAndMergesResults() {
                kbProperties.getQuery().setMemoryEnabled(true);
                QueryRequest request = createRequest("req-mem-1", "#kb 问题");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_ONLY);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("检索内容", 0.85, "doc-1", "ds-1", null)));
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-mem-1")))
                                .thenReturn(retrievalResponse);

                MemorySearchResponse memoryResponse = new MemorySearchResponse(List.of(
                                new MemorySearchResponse.MemoryChunk("Memory 内容", 0.75)));
                when(ragflowClient.searchMemory(any(MemorySearchRequest.class), eq("req-mem-1")))
                                .thenReturn(memoryResponse);

                List<EvidenceSource> mergedSources = List.of(
                                new EvidenceSource("ds-1", "doc-1", "检索内容", 0.85, Map.of()),
                                new EvidenceSource("memory", "memory", "Memory 内容", 0.75, Map.of("source", "memory")));
                QueryResponse expectedResponse = new QueryResponse("req-mem-1", QueryRoute.KB_ONLY, false,
                                mergedSources, List.of("只依据 sources 回答"),
                                new RetrievalQuality(2, Confidence.HIGH, false, 2));
                when(evidencePackBuilder.build(eq("req-mem-1"), eq(QueryRoute.KB_ONLY), any()))
                                .thenReturn(expectedResponse);

                QueryResponse response = queryService.query(request);

                assertEquals(2, response.sources().size());
                verify(ragflowClient).searchMemory(any(MemorySearchRequest.class), eq("req-mem-1"));

                ArgumentCaptor<List<EvidenceSource>> sourcesCaptor = ArgumentCaptor.captor();
                verify(evidencePackBuilder).build(eq("req-mem-1"), eq(QueryRoute.KB_ONLY), sourcesCaptor.capture());
                assertEquals(2, sourcesCaptor.getValue().size());
        }

        @Test
        @DisplayName("KB_PLUS_LLM + memoryEnabled=true 时调用 Memory 检索")
        void query_kbPlusLlm_memoryEnabled_callsSearchMemory() {
                kbProperties.getQuery().setMemoryEnabled(true);
                QueryRequest request = createRequest("req-mem-2", "问题");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_PLUS_LLM);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("检索内容", 0.80, "doc-1", "ds-1", null)));
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-mem-2")))
                                .thenReturn(retrievalResponse);

                MemorySearchResponse memoryResponse = new MemorySearchResponse(List.of(
                                new MemorySearchResponse.MemoryChunk("Memory 内容", 0.70)));
                when(ragflowClient.searchMemory(any(MemorySearchRequest.class), eq("req-mem-2")))
                                .thenReturn(memoryResponse);

                QueryResponse expectedResponse = new QueryResponse("req-mem-2", QueryRoute.KB_PLUS_LLM, true,
                                List.of(), List.of("优先依据 sources 回答"),
                                new RetrievalQuality(2, Confidence.HIGH, false, 2));
                when(evidencePackBuilder.build(any(), any(), any())).thenReturn(expectedResponse);

                queryService.query(request);
                verify(ragflowClient).searchMemory(any(MemorySearchRequest.class), eq("req-mem-2"));
        }

        @Test
        @DisplayName("memoryEnabled=false 时不调用 Memory 检索")
        void query_memoryDisabled_doesNotCallSearchMemory() {
                kbProperties.getQuery().setMemoryEnabled(false);
                QueryRequest request = createRequest("req-mem-4", "问题");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_ONLY);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("内容", 0.85, "doc-1", "ds-1", null)));
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-mem-4")))
                                .thenReturn(retrievalResponse);

                QueryResponse expectedResponse = new QueryResponse("req-mem-4", QueryRoute.KB_ONLY, false,
                                List.of(), List.of("只依据 sources 回答"),
                                new RetrievalQuality(1, Confidence.HIGH, false, 1));
                when(evidencePackBuilder.build(any(), any(), any())).thenReturn(expectedResponse);

                queryService.query(request);
                verify(ragflowClient, never()).searchMemory(any(), any());
        }

        @Test
        @DisplayName("Memory 检索失败时优雅降级")
        void query_memorySearchFails_continuesWithoutMemoryResults() {
                kbProperties.getQuery().setMemoryEnabled(true);
                QueryRequest request = createRequest("req-mem-5", "问题");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_ONLY);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("检索内容", 0.85, "doc-1", "ds-1", null)));
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-mem-5")))
                                .thenReturn(retrievalResponse);
                when(ragflowClient.searchMemory(any(MemorySearchRequest.class), eq("req-mem-5")))
                                .thenThrow(new ExternalServiceException("Memory timeout", "req-mem-5", "RAGFlow", 504));

                QueryResponse expectedResponse = new QueryResponse("req-mem-5", QueryRoute.KB_ONLY, false,
                                List.of(new EvidenceSource("ds-1", "doc-1", "检索内容", 0.85, Map.of())),
                                List.of("只依据 sources 回答"),
                                new RetrievalQuality(1, Confidence.HIGH, false, 1));
                when(evidencePackBuilder.build(any(), any(), any())).thenReturn(expectedResponse);

                QueryResponse response = queryService.query(request);
                assertNotNull(response);

                ArgumentCaptor<List<EvidenceSource>> sourcesCaptor = ArgumentCaptor.captor();
                verify(evidencePackBuilder).build(any(), any(), sourcesCaptor.capture());
                assertEquals(1, sourcesCaptor.getValue().size());
        }

        @Test
        @DisplayName("Memory 结果与主检索结果重复时去重")
        void query_memoryResultsDuplicate_deduplicates() {
                kbProperties.getQuery().setMemoryEnabled(true);
                QueryRequest request = createRequest("req-mem-6", "问题");
                when(queryRouter.route(request)).thenReturn(QueryRoute.KB_ONLY);
                when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
                when(queryLogMapper.updateById(any(QueryLogEntity.class))).thenReturn(1);

                RetrievalResponse retrievalResponse = RetrievalResponse.ofChunks(List.of(
                                new RetrievalResponse.Chunk("相同内容", 0.85, "doc-1", "ds-1", null)));
                when(ragflowClient.retrieval(any(RetrievalRequest.class), eq("req-mem-6")))
                                .thenReturn(retrievalResponse);

                MemorySearchResponse memoryResponse = new MemorySearchResponse(List.of(
                                new MemorySearchResponse.MemoryChunk("相同内容", 0.70)));
                when(ragflowClient.searchMemory(any(MemorySearchRequest.class), eq("req-mem-6")))
                                .thenReturn(memoryResponse);

                QueryResponse expectedResponse = new QueryResponse("req-mem-6", QueryRoute.KB_ONLY, false,
                                List.of(), List.of("只依据 sources 回答"),
                                new RetrievalQuality(1, Confidence.HIGH, false, 1));
                when(evidencePackBuilder.build(any(), any(), any())).thenReturn(expectedResponse);

                queryService.query(request);

                ArgumentCaptor<List<EvidenceSource>> sourcesCaptor = ArgumentCaptor.captor();
                verify(evidencePackBuilder).build(any(), any(), sourcesCaptor.capture());
                assertEquals(1, sourcesCaptor.getValue().size());
        }

        // ── searchMemory / dedup 单元测试 ──

        @Test
        void searchMemory_returnsEmptyWhenResponseIsNull() {
                when(ragflowClient.searchMemory(any(), eq("req-1"))).thenReturn(null);
                assertTrue(queryService.searchMemory(createRequest("req-1", "q")).isEmpty());
        }

        @Test
        void searchMemory_returnsEmptyWhenChunksEmpty() {
                when(ragflowClient.searchMemory(any(), eq("req-1")))
                                .thenReturn(new MemorySearchResponse(List.of()));
                assertTrue(queryService.searchMemory(createRequest("req-1", "q")).isEmpty());
        }

        @Test
        void searchMemory_convertsChunksToEvidenceSources() {
                MemorySearchResponse resp = new MemorySearchResponse(List.of(
                                new MemorySearchResponse.MemoryChunk("内容1", 0.9),
                                new MemorySearchResponse.MemoryChunk("内容2", 0.7)));
                when(ragflowClient.searchMemory(any(), eq("req-1"))).thenReturn(resp);
                List<EvidenceSource> result = queryService.searchMemory(createRequest("req-1", "q"));
                assertEquals(2, result.size());
                assertEquals("memory", result.get(0).dataset());
        }

        @Test
        void searchMemory_returnsEmptyOnException() {
                when(ragflowClient.searchMemory(any(), eq("req-1")))
                                .thenThrow(new RuntimeException("connection refused"));
                assertTrue(queryService.searchMemory(createRequest("req-1", "q")).isEmpty());
        }

        @Test
        void dedup_removesDuplicates() {
                List<EvidenceSource> sources = List.of(
                                new EvidenceSource("ds-1", "doc-1", "内容A", 0.9, Map.of()),
                                new EvidenceSource("memory", "memory", "内容A", 0.7, Map.of()),
                                new EvidenceSource("ds-1", "doc-2", "内容B", 0.8, Map.of()));
                assertEquals(2, queryService.dedup(sources).size());
        }

        @Test
        void dedup_handlesEmptyList() {
                assertTrue(queryService.dedup(List.of()).isEmpty());
        }

        @Test
        void dedup_handlesNull() {
                assertTrue(queryService.dedup(null).isEmpty());
        }
}
