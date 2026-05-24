package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ragflow.*;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * RagflowClient 的 Spring WebClient 实现。
 * <p>
 * 特性：
 * <ul>
 * <li>统一超时（kb.ragflow.timeout-ms，默认 5s）</li>
 * <li>HTTP 错误自动转换为 ExternalServiceException</li>
 * <li>透传 requestId（X-Request-Id 请求头）</li>
 * <li>日志打印完整的请求和响应内容</li>
 * <li>幂等接口支持重试（最多 3 次，间隔 1s）</li>
 * </ul>
 * </p>
 */
@Component
public class RagflowClientImpl implements RagflowClient {

        private static final Logger log = LoggerFactory.getLogger(RagflowClientImpl.class);

        private static final String SERVICE_NAME = "RAGFlow";
        private static final String REQUEST_ID_HEADER = "X-Request-Id";

        private final WebClient webClient;
        private final KbProperties.Ragflow ragflowConfig;

        /**
         * 构造函数，通过 WebClient.Builder 和 KbProperties 初始化 WebClient。
         *
         * @param webClientBuilder Spring 注入的 WebClient.Builder
         * @param kbProperties     统一配置
         */
        public RagflowClientImpl(WebClient.Builder webClientBuilder, KbProperties kbProperties) {
                this.ragflowConfig = kbProperties.getRagflow();
                this.webClient = webClientBuilder
                                .baseUrl(ragflowConfig.getBaseUrl())
                                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ragflowConfig.getApiKey())
                                .build();
        }

        /**
         * 调用 RAGFlow 检索接口（幂等操作，支持重试）。
         *
         * @param request   检索请求
         * @param requestId 请求唯一标识，用于透传和日志追踪
         * @return 检索响应
         * @throws ExternalServiceException 当 RAGFlow 返回 HTTP 错误或调用超时时抛出
         */
        @Override
        public RetrievalResponse retrieval(RetrievalRequest request, String requestId) {
                log.info("[{}] RAGFlow retrieval 请求: {}", requestId, request);

                long startTime = System.currentTimeMillis();
                RetrievalResponse response = executeWithRetry("retrieval", requestId, () -> {
                        String rawResponse = webClient.post()
                                        .uri("/api/v1/retrieval")
                                        .header(REQUEST_ID_HEADER, requestId)
                                        .bodyValue(request)
                                        .retrieve()
                                        .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse
                                                        .bodyToMono(String.class)
                                                        .defaultIfEmpty("")
                                                        .map(body -> new WebClientResponseException(
                                                                        clientResponse.statusCode().value(),
                                                                        "RAGFlow retrieval error: " + summarize(body),
                                                                        null, null, null)))
                                        .bodyToMono(String.class)
                                        .block(Duration.ofMillis(ragflowConfig.getTimeoutMs()));
                        log.info("[{}] RAGFlow retrieval 原始响应: {}", requestId, JsonUtil.unescapeUnicode(rawResponse));
                        try {
                                RetrievalResponse parsed = new tools.jackson.databind.ObjectMapper()
                                                .readValue(rawResponse, RetrievalResponse.class);
                                if (parsed.code() != null && parsed.code() != 0) {
                                        log.warn("[{}] RAGFlow retrieval 业务错误: code={}, message={}",
                                                        requestId, parsed.code(), parsed.message());
                                }
                                return parsed;
                        } catch (Exception e) {
                                log.error("[{}] RAGFlow retrieval 响应反序列化失败: {}, 原始响应: {}",
                                                requestId, e.getMessage(), JsonUtil.unescapeUnicode(rawResponse));
                                return new RetrievalResponse(0, null, null);
                        }
                });

                log.info("[{}] RAGFlow retrieval 完成: chunks={}, latencyMs={}", requestId,
                                response != null && response.chunks() != null ? response.chunks().size() : 0,
                                System.currentTimeMillis() - startTime);
                return response;
        }

        /**
         * 在 RAGFlow 数据集中创建/注册文档（幂等操作，支持重试）。
         *
         * @param request   创建文档请求
         * @param requestId 请求唯一标识，用于透传和日志追踪
         * @return 创建文档响应
         * @throws ExternalServiceException 当 RAGFlow 返回 HTTP 错误或调用超时时抛出
         */
        @Override
        public CreateDocumentResponse createDocument(CreateDocumentRequest request, String requestId) {
                log.info("[{}] RAGFlow createDocument 请求: {}", requestId, request);

                CreateDocumentResponse response = executeWithRetry("createDocument", requestId, () -> webClient.post()
                                .uri("/api/v1/datasets/{datasetId}/documents", request.datasetId())
                                .header(REQUEST_ID_HEADER, requestId)
                                .bodyValue(request)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .map(body -> new WebClientResponseException(
                                                                clientResponse.statusCode().value(),
                                                                "RAGFlow createDocument error: " + summarize(body),
                                                                null, null, null)))
                                .bodyToMono(CreateDocumentResponse.class)
                                .block(Duration.ofMillis(ragflowConfig.getTimeoutMs())));

                log.info("[{}] RAGFlow createDocument 响应: {}", requestId, response);
                return response;
        }

        /**
         * 创建 RAGFlow 数据集（幂等操作，支持重试）。
         *
         * @param request   创建数据集请求
         * @param requestId 请求唯一标识，用于透传和日志追踪
         * @return 创建数据集响应
         * @throws ExternalServiceException 当 RAGFlow 返回 HTTP 错误或调用超时时抛出
         */
        @Override
        public CreateDatasetResponse createDataset(CreateDatasetRequest request, String requestId) {
                log.info("[{}] RAGFlow createDataset 请求: {}", requestId, request);

                CreateDatasetResponse response = executeWithRetry("createDataset", requestId, () -> webClient.post()
                                .uri("/api/v1/datasets")
                                .header(REQUEST_ID_HEADER, requestId)
                                .bodyValue(request)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .map(body -> new WebClientResponseException(
                                                                clientResponse.statusCode().value(),
                                                                "RAGFlow createDataset error: " + summarize(body),
                                                                null, null, null)))
                                .bodyToMono(CreateDatasetResponse.class)
                                .block(Duration.ofMillis(ragflowConfig.getTimeoutMs())));

                log.info("[{}] RAGFlow createDataset 响应: {}", requestId, response);
                return response;
        }

        /**
         * 调用 RAGFlow Memory 检索接口（幂等操作，支持重试）。
         *
         * @param request   Memory 检索请求
         * @param requestId 请求唯一标识，用于透传和日志追踪
         * @return Memory 检索响应
         * @throws ExternalServiceException 当 RAGFlow 返回 HTTP 错误或调用超时时抛出
         */
        @Override
        public MemorySearchResponse searchMemory(MemorySearchRequest request, String requestId) {
                log.info("[{}] RAGFlow searchMemory 请求: {}", requestId, request);

                MemorySearchResponse response = executeWithRetry("searchMemory", requestId, () -> webClient.post()
                                .uri("/api/v1/retrieval/memory")
                                .header(REQUEST_ID_HEADER, requestId)
                                .bodyValue(request)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .map(body -> new WebClientResponseException(
                                                                clientResponse.statusCode().value(),
                                                                "RAGFlow searchMemory error: " + summarize(body),
                                                                null, null, null)))
                                .bodyToMono(MemorySearchResponse.class)
                                .block(Duration.ofMillis(ragflowConfig.getTimeoutMs())));

                log.info("[{}] RAGFlow searchMemory 响应: {}", requestId, response);
                return response;
        }

        /**
         * 删除 RAGFlow 数据集中的文档（幂等操作，支持重试）。
         *
         * @param datasetId  数据集 ID
         * @param documentId 文档 ID
         * @param requestId  请求唯一标识，用于透传和日志追踪
         * @return 删除文档响应
         * @throws ExternalServiceException 当 RAGFlow 返回 HTTP 错误或调用超时时抛出
         */
        @Override
        public DeleteDocumentResponse deleteDocument(String datasetId, String documentId, String requestId) {
                log.info("[{}] RAGFlow deleteDocument 请求: datasetId={}, documentId={}",
                                requestId, datasetId, documentId);

                DeleteDocumentResponse response = executeWithRetry("deleteDocument", requestId, () -> webClient
                                .delete()
                                .uri("/api/v1/datasets/{datasetId}/documents/{documentId}", datasetId, documentId)
                                .header(REQUEST_ID_HEADER, requestId)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .map(body -> new WebClientResponseException(
                                                                clientResponse.statusCode().value(),
                                                                "RAGFlow deleteDocument error: " + summarize(body),
                                                                null, null, null)))
                                .bodyToMono(DeleteDocumentResponse.class)
                                .block(Duration.ofMillis(ragflowConfig.getTimeoutMs())));

                log.info("[{}] RAGFlow deleteDocument 响应: {}", requestId, response);
                return response;
        }

        /**
         * 更新 RAGFlow 数据集中的文档（幂等操作，支持重试）。
         *
         * @param request   更新文档请求
         * @param requestId 请求唯一标识，用于透传和日志追踪
         * @return 更新文档响应
         * @throws ExternalServiceException 当 RAGFlow 返回 HTTP 错误或调用超时时抛出
         */
        @Override
        public UpdateDocumentResponse updateDocument(UpdateDocumentRequest request, String requestId) {
                log.info("[{}] RAGFlow updateDocument 请求: {}", requestId, request);

                UpdateDocumentResponse response = executeWithRetry("updateDocument", requestId, () -> webClient.put()
                                .uri("/api/v1/datasets/{datasetId}/documents/{documentId}",
                                                request.datasetId(), request.documentId())
                                .header(REQUEST_ID_HEADER, requestId)
                                .bodyValue(request)
                                .retrieve()
                                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse
                                                .bodyToMono(String.class)
                                                .defaultIfEmpty("")
                                                .map(body -> new WebClientResponseException(
                                                                clientResponse.statusCode().value(),
                                                                "RAGFlow updateDocument error: " + summarize(body),
                                                                null, null, null)))
                                .bodyToMono(UpdateDocumentResponse.class)
                                .block(Duration.ofMillis(ragflowConfig.getTimeoutMs())));

                log.info("[{}] RAGFlow updateDocument 响应: {}", requestId, response);
                return response;
        }

        /**
         * 通用重试执行器，封装重试循环、异常转换和日志记录。
         * <p>
         * 所有幂等的 RAGFlow 调用共用此方法，避免重复的重试逻辑。
         * </p>
         *
         * @param operation 操作名称，用于日志和异常消息
         * @param requestId 请求唯一标识
         * @param action    实际的 HTTP 调用逻辑
         * @param <T>       响应类型
         * @return 调用成功时的响应对象
         * @throws ExternalServiceException 当重试耗尽后仍失败时抛出
         */
        private <T> T executeWithRetry(String operation, String requestId, Supplier<T> action) {
                int maxAttempts = ragflowConfig.getRetryMaxAttempts();
                long retryDelayMs = ragflowConfig.getRetryDelayMs();

                ExternalServiceException lastException = null;

                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                                return action.get();

                        } catch (WebClientResponseException ex) {
                                lastException = new ExternalServiceException(
                                                "RAGFlow " + operation + " HTTP error: " + ex.getStatusCode(),
                                                requestId, SERVICE_NAME, ex.getStatusCode().value(), ex);
                                log.warn("[{}] RAGFlow {} 第 {}/{} 次失败 (HTTP {}): {}",
                                                requestId, operation, attempt, maxAttempts, ex.getStatusCode().value(),
                                                ex.getMessage());

                        } catch (WebClientRequestException ex) {
                                lastException = new ExternalServiceException(
                                                "RAGFlow " + operation + " 连接失败: " + ex.getMessage(),
                                                requestId, SERVICE_NAME, null, ex);
                                log.warn("[{}] RAGFlow {} 第 {}/{} 次连接失败: {}",
                                                requestId, operation, attempt, maxAttempts, ex.getMessage());

                        } catch (Exception ex) {
                                lastException = new ExternalServiceException(
                                                "RAGFlow " + operation + " 调用异常: " + ex.getMessage(),
                                                requestId, SERVICE_NAME, null, ex);
                                log.warn("[{}] RAGFlow {} 第 {}/{} 次异常: {}",
                                                requestId, operation, attempt, maxAttempts, ex.getMessage());
                        }

                        // 非最后一次尝试时等待重试
                        if (attempt < maxAttempts) {
                                try {
                                        Thread.sleep(retryDelayMs);
                                } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        throw lastException;
                                }
                        }
                }

                log.error("[{}] RAGFlow {} 重试 {} 次后仍失败", requestId, operation, maxAttempts);
                throw lastException;
        }

        /**
         * 将内容转为字符串形式输出到日志，打印完整内容不做截断。
         * <p>
         * 同时还原 {@code \\uXXXX} 转义序列为实际字符，便于阅读
         * （RAGFlow 的 Python 后端默认使用 {@code ensure_ascii=True}）。
         * </p>
         *
         * @param content 原始内容
         * @return 还原后的内容字符串（null 时返回 "null"）
         */
        private String summarize(String content) {
                return JsonUtil.unescapeUnicode(content);
        }
}
