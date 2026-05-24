package com.openclaw.kbbridge.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 KbProperties 配置绑定正确性。
 * 使用内联属性避免依赖外部数据库等基础设施。
 */
class KbPropertiesTest {

    private final KbProperties kbProperties = bindTestProperties();

    @Test
    void ragflowConfigBindsCorrectly() {
        var ragflow = kbProperties.getRagflow();
        assertEquals("http://test-ragflow:9380", ragflow.getBaseUrl());
        assertEquals("test-api-key", ragflow.getApiKey());
        assertEquals(3000, ragflow.getTimeoutMs());
        assertEquals(2, ragflow.getRetryMaxAttempts());
        assertEquals(500, ragflow.getRetryDelayMs());
    }

    @Test
    void queryConfigBindsCorrectly() {
        var query = kbProperties.getQuery();
        assertEquals("rule", query.getRouteStrategy());
        assertEquals("LLM_ONLY", query.getDefaultRoute());
        assertEquals(0.7, query.getScoreThreshold(), 0.001);
        assertEquals(10, query.getMaxSources());
        assertEquals(3000, query.getMaxContentLength());
        assertEquals(10000, query.getMaxTotalLength());
    }

    @Test
    void securityConfigBindsCorrectly() {
        var security = kbProperties.getSecurity();
        assertEquals("test-secret", security.getSharedSecret());
        assertEquals(60000, security.getTimestampToleranceMs());
        assertEquals("HmacSHA256", security.getSignatureAlgorithm());
    }

    @Test
    void defaultValuesAreCorrect() {
        // Verify defaults for Phase 2/3 placeholders
        var minio = kbProperties.getMinio();
        assertEquals("http://localhost:9000", minio.getEndpoint());
        assertEquals("kb-raw", minio.getRawBucket());
        assertEquals("kb-processed", minio.getProcessedBucket());

        var processor = kbProperties.getProcessor();
        assertEquals("openai", processor.getLlmProvider());
        assertEquals(30000, processor.getLlmTimeoutMs());
        assertEquals(3, processor.getMinQaCount());

        var ingest = kbProperties.getIngest();
        assertEquals(4, ingest.getAsyncPoolSize());
        assertEquals(3, ingest.getRetryMaxAttempts());
        assertEquals(5000, ingest.getRetryDelayMs());
        assertEquals("SHA-256", ingest.getContentHashAlgorithm());
    }

    private KbProperties bindTestProperties() {
        Map<String, String> properties = Map.ofEntries(
                Map.entry("kb.ragflow.base-url", "http://test-ragflow:9380"),
                Map.entry("kb.ragflow.api-key", "test-api-key"),
                Map.entry("kb.ragflow.timeout-ms", "3000"),
                Map.entry("kb.ragflow.retry-max-attempts", "2"),
                Map.entry("kb.ragflow.retry-delay-ms", "500"),
                Map.entry("kb.query.route-strategy", "rule"),
                Map.entry("kb.query.default-route", "LLM_ONLY"),
                Map.entry("kb.query.score-threshold", "0.7"),
                Map.entry("kb.query.max-sources", "10"),
                Map.entry("kb.query.max-content-length", "3000"),
                Map.entry("kb.query.max-total-length", "10000"),
                Map.entry("kb.security.shared-secret", "test-secret"),
                Map.entry("kb.security.timestamp-tolerance-ms", "60000"),
                Map.entry("kb.security.signature-algorithm", "HmacSHA256")
        );
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("kb", KbProperties.class)
                .orElse(new KbProperties());
    }
}
