package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ragflow.*;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagflowClientImpl 单元测试。
 * 使用 MockWebServer 模拟 RAGFlow HTTP 服务。
 */
class RagflowClientImplTest {

        private MockWebServer mockWebServer;
        private RagflowClientImpl client;
        private KbProperties kbProperties;

        @BeforeEach
        void setUp() throws IOException {
                mockWebServer = new MockWebServer();
                mockWebServer.start();

                kbProperties = new KbProperties();
                kbProperties.getRagflow().setBaseUrl(mockWebServer.url("/").toString());
                kbProperties.getRagflow().setApiKey("test-api-key");
                kbProperties.getRagflow().setTimeoutMs(5000);
                kbProperties.getRagflow().setRetryMaxAttempts(3);
                kbProperties.getRagflow().setRetryDelayMs(100); // 测试中使用短间隔

                client = new RagflowClientImpl(WebClient.builder(), kbProperties);
        }

        @AfterEach
        void tearDown() throws IOException {
                mockWebServer.shutdown();
        }

        // ── retrieval 测试 ──

        @Test
        void retrieval_success_returnsChunks() throws Exception {
                String responseBody = """
                                {
                                    "code": 0,
                                    "data": {
                                        "chunks": [
                                            {
                                                "content": "这是一段测试内容",
                                                "similarity": 0.95,
                                                "document_keyword": "test-doc.md",
                                                "kb_id": "ds-001",
                                                "document_id": "doc-001"
                                            }
                                        ],
                                        "total": 1
                                    }
                                }
                                """;
                mockWebServer.enqueue(new MockResponse()
                                .setBody(responseBody)
                                .addHeader("Content-Type", "application/json"));

                RetrievalRequest request = new RetrievalRequest(
                                "什么是 Spring Boot?", List.of("ds-001"), 5, 0.6, null);

                RetrievalResponse response = client.retrieval(request, "req-001");

                assertNotNull(response);
                assertNotNull(response.chunks());
                assertEquals(1, response.chunks().size());
                assertEquals("这是一段测试内容", response.chunks().getFirst().content());
                assertEquals(0.95, response.chunks().getFirst().score(), 0.001);
        }

        @Test
        void retrieval_passesRequestIdHeader() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"chunks\": []}")
                                .addHeader("Content-Type", "application/json"));

                RetrievalRequest request = new RetrievalRequest(
                                "test question", List.of("ds-001"), 5, 0.6, null);

                client.retrieval(request, "req-trace-123");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("req-trace-123", recorded.getHeader("X-Request-Id"));
        }

        @Test
        void retrieval_passesAuthorizationHeader() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"chunks\": []}")
                                .addHeader("Content-Type", "application/json"));

                RetrievalRequest request = new RetrievalRequest(
                                "test question", List.of("ds-001"), 5, 0.6, null);

                client.retrieval(request, "req-001");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        }

        @Test
        void retrieval_postsToCorrectPath() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"chunks\": []}")
                                .addHeader("Content-Type", "application/json"));

                RetrievalRequest request = new RetrievalRequest(
                                "test question", List.of("ds-001"), 5, 0.6, null);

                client.retrieval(request, "req-001");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("/api/v1/retrieval", recorded.getPath());
                assertEquals("POST", recorded.getMethod());
        }

        @Test
        void retrieval_httpError_throwsExternalServiceException() {
                // 所有 3 次重试都返回 500
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(500)
                                        .setBody("{\"error\": \"internal error\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                RetrievalRequest request = new RetrievalRequest(
                                "test question", List.of("ds-001"), 5, 0.6, null);

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.retrieval(request, "req-err-001"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals("req-err-001", ex.getRequestId());
                assertNotNull(ex.getStatusCode());
                assertEquals(500, ex.getStatusCode());
        }

        @Test
        void retrieval_retries_onFailureThenSuccess() throws Exception {
                // 第一次失败
                mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(503)
                                .setBody("{\"error\": \"service unavailable\"}")
                                .addHeader("Content-Type", "application/json"));
                // 第二次成功
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"code\":0,\"data\":{\"chunks\":[{\"content\":\"ok\",\"similarity\":0.9,\"document_keyword\":\"doc\",\"kb_id\":\"ds\",\"document_id\":null}],\"total\":1}}")
                                .addHeader("Content-Type", "application/json"));

                RetrievalRequest request = new RetrievalRequest(
                                "test question", List.of("ds-001"), 5, 0.6, null);

                RetrievalResponse response = client.retrieval(request, "req-retry-001");

                assertNotNull(response);
                assertEquals(1, response.chunks().size());
                assertEquals("ok", response.chunks().getFirst().content());
                assertEquals(2, mockWebServer.getRequestCount());
        }

        @Test
        void retrieval_exhaustsRetries_throwsException() {
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(502)
                                        .setBody("{\"error\": \"bad gateway\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                RetrievalRequest request = new RetrievalRequest(
                                "test question", List.of("ds-001"), 5, 0.6, null);

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.retrieval(request, "req-exhaust-001"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals(3, mockWebServer.getRequestCount());
        }

        @Test
        void retrieval_emptyChunks_returnsEmptyList() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"chunks\": []}")
                                .addHeader("Content-Type", "application/json"));

                RetrievalRequest request = new RetrievalRequest(
                                "unknown topic", List.of("ds-001"), 5, 0.6, null);

                RetrievalResponse response = client.retrieval(request, "req-empty-001");

                assertNotNull(response);
                assertNotNull(response.chunks());
                assertTrue(response.chunks().isEmpty());
        }

        // ── createDocument 测试 ──

        @Test
        void createDocument_success_returnsDocumentId() throws Exception {
                String responseBody = """
                                {
                                    "documentId": "doc-create-001",
                                    "name": "测试文档"
                                }
                                """;
                mockWebServer.enqueue(new MockResponse()
                                .setBody(responseBody)
                                .addHeader("Content-Type", "application/json"));

                CreateDocumentRequest request = new CreateDocumentRequest(
                                "ds-001", "测试文档", "文档内容", Map.of("topic", "test"));

                CreateDocumentResponse response = client.createDocument(request, "req-create-001");

                assertNotNull(response);
                assertEquals("doc-create-001", response.documentId());
                assertEquals("测试文档", response.name());
        }

        @Test
        void createDocument_postsToCorrectPath() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"documentId\": \"doc-001\", \"name\": \"doc\"}")
                                .addHeader("Content-Type", "application/json"));

                CreateDocumentRequest request = new CreateDocumentRequest(
                                "ds-xyz", "doc", "content", null);

                client.createDocument(request, "req-path-create");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("/api/v1/datasets/ds-xyz/documents", recorded.getPath());
                assertEquals("POST", recorded.getMethod());
        }

        @Test
        void createDocument_passesRequestIdAndAuthHeaders() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"documentId\": \"doc-001\", \"name\": \"doc\"}")
                                .addHeader("Content-Type", "application/json"));

                CreateDocumentRequest request = new CreateDocumentRequest(
                                "ds-001", "doc", "content", null);

                client.createDocument(request, "req-header-create");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("req-header-create", recorded.getHeader("X-Request-Id"));
                assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        }

        @Test
        void createDocument_httpError_throwsExternalServiceException() {
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(502)
                                        .setBody("{\"error\": \"bad gateway\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                CreateDocumentRequest request = new CreateDocumentRequest(
                                "ds-001", "doc", "content", null);

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.createDocument(request, "req-create-err"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals("req-create-err", ex.getRequestId());
                assertNotNull(ex.getStatusCode());
                assertEquals(502, ex.getStatusCode());
        }

        @Test
        void createDocument_retries_onFailureThenSuccess() throws Exception {
                // 第一次失败
                mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(500)
                                .setBody("{\"error\": \"error\"}")
                                .addHeader("Content-Type", "application/json"));
                // 第二次成功
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"documentId\": \"doc-retry\", \"name\": \"doc\"}")
                                .addHeader("Content-Type", "application/json"));

                CreateDocumentRequest request = new CreateDocumentRequest(
                                "ds-001", "doc", "content", Map.of("key", "value"));

                CreateDocumentResponse response = client.createDocument(request, "req-create-retry");

                assertNotNull(response);
                assertEquals("doc-retry", response.documentId());
                assertEquals(2, mockWebServer.getRequestCount());
        }

        @Test
        void createDocument_exhaustsRetries_throwsException() {
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(503)
                                        .setBody("{\"error\": \"unavailable\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                CreateDocumentRequest request = new CreateDocumentRequest(
                                "ds-001", "doc", "content", null);

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.createDocument(request, "req-create-exhaust"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals(3, mockWebServer.getRequestCount());
        }

        // ── createDataset 测试 ──

        @Test
        void createDataset_success_returnsDatasetId() throws Exception {
                String responseBody = """
                                {
                                    "datasetId": "ds-new-001",
                                    "name": "测试数据集"
                                }
                                """;
                mockWebServer.enqueue(new MockResponse()
                                .setBody(responseBody)
                                .addHeader("Content-Type", "application/json"));

                CreateDatasetRequest request = new CreateDatasetRequest("测试数据集", "数据集描述");

                CreateDatasetResponse response = client.createDataset(request, "req-ds-001");

                assertNotNull(response);
                assertEquals("ds-new-001", response.datasetId());
                assertEquals("测试数据集", response.name());
        }

        @Test
        void createDataset_postsToCorrectPath() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"datasetId\": \"ds-001\", \"name\": \"ds\"}")
                                .addHeader("Content-Type", "application/json"));

                CreateDatasetRequest request = new CreateDatasetRequest("ds", "desc");

                client.createDataset(request, "req-ds-path");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("/api/v1/datasets", recorded.getPath());
                assertEquals("POST", recorded.getMethod());
        }

        @Test
        void createDataset_passesRequestIdHeader() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"datasetId\": \"ds-001\", \"name\": \"ds\"}")
                                .addHeader("Content-Type", "application/json"));

                CreateDatasetRequest request = new CreateDatasetRequest("ds", "desc");

                client.createDataset(request, "req-ds-header");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("req-ds-header", recorded.getHeader("X-Request-Id"));
                assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        }

        @Test
        void createDataset_httpError_throwsExternalServiceException() {
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(500)
                                        .setBody("{\"error\": \"internal error\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                CreateDatasetRequest request = new CreateDatasetRequest("ds", "desc");

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.createDataset(request, "req-ds-err"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals("req-ds-err", ex.getRequestId());
                assertEquals(500, ex.getStatusCode());
        }

        @Test
        void createDataset_retries_onFailureThenSuccess() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(503)
                                .setBody("{\"error\": \"unavailable\"}")
                                .addHeader("Content-Type", "application/json"));
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"datasetId\": \"ds-retry\", \"name\": \"ds\"}")
                                .addHeader("Content-Type", "application/json"));

                CreateDatasetRequest request = new CreateDatasetRequest("ds", "desc");

                CreateDatasetResponse response = client.createDataset(request, "req-ds-retry");

                assertNotNull(response);
                assertEquals("ds-retry", response.datasetId());
                assertEquals(2, mockWebServer.getRequestCount());
        }

        // ── searchMemory 测试 ──

        @Test
        void searchMemory_success_returnsChunks() throws Exception {
                String responseBody = """
                                {
                                    "chunks": [
                                        {
                                            "content": "Memory 检索结果",
                                            "score": 0.88
                                        }
                                    ]
                                }
                                """;
                mockWebServer.enqueue(new MockResponse()
                                .setBody(responseBody)
                                .addHeader("Content-Type", "application/json"));

                MemorySearchRequest request = new MemorySearchRequest("什么是知识库?", "ds-001");

                MemorySearchResponse response = client.searchMemory(request, "req-mem-001");

                assertNotNull(response);
                assertNotNull(response.chunks());
                assertEquals(1, response.chunks().size());
                assertEquals("Memory 检索结果", response.chunks().getFirst().content());
                assertEquals(0.88, response.chunks().getFirst().score(), 0.001);
        }

        @Test
        void searchMemory_postsToCorrectPath() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"chunks\": []}")
                                .addHeader("Content-Type", "application/json"));

                MemorySearchRequest request = new MemorySearchRequest("test", "ds-001");

                client.searchMemory(request, "req-mem-path");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("/api/v1/retrieval/memory", recorded.getPath());
                assertEquals("POST", recorded.getMethod());
        }

        @Test
        void searchMemory_passesRequestIdHeader() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"chunks\": []}")
                                .addHeader("Content-Type", "application/json"));

                MemorySearchRequest request = new MemorySearchRequest("test", "ds-001");

                client.searchMemory(request, "req-mem-header");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("req-mem-header", recorded.getHeader("X-Request-Id"));
        }

        @Test
        void searchMemory_httpError_throwsExternalServiceException() {
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(500)
                                        .setBody("{\"error\": \"error\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                MemorySearchRequest request = new MemorySearchRequest("test", "ds-001");

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.searchMemory(request, "req-mem-err"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals(500, ex.getStatusCode());
        }

        @Test
        void searchMemory_emptyChunks_returnsEmptyList() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"chunks\": []}")
                                .addHeader("Content-Type", "application/json"));

                MemorySearchRequest request = new MemorySearchRequest("unknown", "ds-001");

                MemorySearchResponse response = client.searchMemory(request, "req-mem-empty");

                assertNotNull(response);
                assertNotNull(response.chunks());
                assertTrue(response.chunks().isEmpty());
        }

        // ── deleteDocument 测试 ──

        @Test
        void deleteDocument_success_returnsTrue() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"success\": true}")
                                .addHeader("Content-Type", "application/json"));

                DeleteDocumentResponse response = client.deleteDocument("ds-001", "doc-001", "req-del-001");

                assertNotNull(response);
                assertTrue(response.success());
        }

        @Test
        void deleteDocument_usesCorrectPathAndMethod() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"success\": true}")
                                .addHeader("Content-Type", "application/json"));

                client.deleteDocument("ds-abc", "doc-xyz", "req-del-path");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("/api/v1/datasets/ds-abc/documents/doc-xyz", recorded.getPath());
                assertEquals("DELETE", recorded.getMethod());
        }

        @Test
        void deleteDocument_passesRequestIdHeader() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"success\": true}")
                                .addHeader("Content-Type", "application/json"));

                client.deleteDocument("ds-001", "doc-001", "req-del-header");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("req-del-header", recorded.getHeader("X-Request-Id"));
                assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        }

        @Test
        void deleteDocument_httpError_throwsExternalServiceException() {
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(404)
                                        .setBody("{\"error\": \"not found\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.deleteDocument("ds-001", "doc-bad", "req-del-err"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals(404, ex.getStatusCode());
        }

        @Test
        void deleteDocument_retries_onFailureThenSuccess() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(503)
                                .setBody("{\"error\": \"unavailable\"}")
                                .addHeader("Content-Type", "application/json"));
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"success\": true}")
                                .addHeader("Content-Type", "application/json"));

                DeleteDocumentResponse response = client.deleteDocument("ds-001", "doc-001", "req-del-retry");

                assertNotNull(response);
                assertTrue(response.success());
                assertEquals(2, mockWebServer.getRequestCount());
        }

        // ── updateDocument 测试 ──

        @Test
        void updateDocument_success_returnsResponse() throws Exception {
                String responseBody = """
                                {
                                    "documentId": "doc-upd-001",
                                    "success": true
                                }
                                """;
                mockWebServer.enqueue(new MockResponse()
                                .setBody(responseBody)
                                .addHeader("Content-Type", "application/json"));

                UpdateDocumentRequest request = new UpdateDocumentRequest(
                                "ds-001", "doc-upd-001", "更新文档名", "COMPLETED");

                UpdateDocumentResponse response = client.updateDocument(request, "req-upd-001");

                assertNotNull(response);
                assertEquals("doc-upd-001", response.documentId());
                assertTrue(response.success());
        }

        @Test
        void updateDocument_usesCorrectPathAndMethod() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"documentId\": \"doc-001\", \"success\": true}")
                                .addHeader("Content-Type", "application/json"));

                UpdateDocumentRequest request = new UpdateDocumentRequest(
                                "ds-abc", "doc-xyz", "name", "COMPLETED");

                client.updateDocument(request, "req-upd-path");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("/api/v1/datasets/ds-abc/documents/doc-xyz", recorded.getPath());
                assertEquals("PUT", recorded.getMethod());
        }

        @Test
        void updateDocument_passesRequestIdHeader() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"documentId\": \"doc-001\", \"success\": true}")
                                .addHeader("Content-Type", "application/json"));

                UpdateDocumentRequest request = new UpdateDocumentRequest(
                                "ds-001", "doc-001", "name", "COMPLETED");

                client.updateDocument(request, "req-upd-header");

                RecordedRequest recorded = mockWebServer.takeRequest();
                assertEquals("req-upd-header", recorded.getHeader("X-Request-Id"));
                assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"));
        }

        @Test
        void updateDocument_httpError_throwsExternalServiceException() {
                for (int i = 0; i < 3; i++) {
                        mockWebServer.enqueue(new MockResponse()
                                        .setResponseCode(500)
                                        .setBody("{\"error\": \"internal error\"}")
                                        .addHeader("Content-Type", "application/json"));
                }

                UpdateDocumentRequest request = new UpdateDocumentRequest(
                                "ds-001", "doc-001", "name", "COMPLETED");

                ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                                () -> client.updateDocument(request, "req-upd-err"));

                assertEquals("RAGFlow", ex.getServiceName());
                assertEquals(500, ex.getStatusCode());
        }

        @Test
        void updateDocument_retries_onFailureThenSuccess() throws Exception {
                mockWebServer.enqueue(new MockResponse()
                                .setResponseCode(502)
                                .setBody("{\"error\": \"bad gateway\"}")
                                .addHeader("Content-Type", "application/json"));
                mockWebServer.enqueue(new MockResponse()
                                .setBody("{\"documentId\": \"doc-retry\", \"success\": true}")
                                .addHeader("Content-Type", "application/json"));

                UpdateDocumentRequest request = new UpdateDocumentRequest(
                                "ds-001", "doc-001", "name", "COMPLETED");

                UpdateDocumentResponse response = client.updateDocument(request, "req-upd-retry");

                assertNotNull(response);
                assertTrue(response.success());
                assertEquals(2, mockWebServer.getRequestCount());
        }
}
