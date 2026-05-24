package com.openclaw.kbbridge.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Health indicator for RAGFlow connectivity.
 */
@Component
public class RagflowHealthIndicator implements HealthIndicator {

    private final WebClient webClient;
    private final KbProperties.Ragflow ragflowConfig;

    public RagflowHealthIndicator(WebClient.Builder webClientBuilder, KbProperties kbProperties) {
        this.ragflowConfig = kbProperties.getRagflow();
        this.webClient = webClientBuilder
                .baseUrl(ragflowConfig.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ragflowConfig.getApiKey())
                .build();
    }

    @Override
    public Health health() {
        try {
            webClient.get()
                    .uri("/api/v1/datasets?page=1&page_size=1")
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(ragflowConfig.getTimeoutMs()));
            return Health.up()
                    .withDetail("baseUrl", ragflowConfig.getBaseUrl())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("baseUrl", ragflowConfig.getBaseUrl())
                    .build();
        }
    }
}
