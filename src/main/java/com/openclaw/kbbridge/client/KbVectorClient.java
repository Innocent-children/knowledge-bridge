package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class KbVectorClient {
    private final WebClient webClient;
    private final KbProperties.KbVector properties;

    public KbVectorClient(WebClient.Builder builder, KbProperties kbProperties) {
        this.properties = kbProperties.getKbVector();
        this.webClient = builder.baseUrl(properties.getBaseUrl()).build();
    }

    public CreateResult createDocument(String title, List<Chunk> chunks, String idempotencyKey) {
        try {
            CreateResult result = webClient.post()
                    .uri("/api/v1/datasets/{datasetId}/documents", properties.getDatasetId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .bodyValue(Map.of("title", title, "chunks", chunks,
                            "idempotency_key", idempotencyKey))
                    .retrieve().bodyToMono(CreateResult.class)
                    .block(Duration.ofMillis(properties.getTimeoutMs()));
            if (result == null) {
                throw new IllegalStateException("KBVector returned an empty response");
            }
            return result;
        } catch (Exception e) {
            throw new ExternalServiceException("KBVector 文档创建失败", null, "KBVector", null, e);
        }
    }

    public void enableDocument(String documentId) {
        postLifecycle(documentId, "enable");
    }

    public void disableDocument(String documentId) {
        postLifecycle(documentId, "disable");
    }

    public void deleteDocument(String documentId) {
        webClient.delete()
                .uri("/api/v1/datasets/{datasetId}/documents/{documentId}",
                        properties.getDatasetId(), documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .retrieve().toBodilessEntity()
                .block(Duration.ofMillis(properties.getTimeoutMs()));
    }

    private void postLifecycle(String documentId, String action) {
        webClient.post()
                .uri("/api/v1/datasets/{datasetId}/documents/{documentId}/{action}",
                        properties.getDatasetId(), documentId, action)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .retrieve().toBodilessEntity()
                .block(Duration.ofMillis(properties.getTimeoutMs()));
    }

    public record Chunk(String content, String metadata_json) {
    }

    public record CreateResult(String document_id, String job_id, String status) {
    }
}
