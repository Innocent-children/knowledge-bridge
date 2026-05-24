package com.openclaw.kbbridge.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthIndicatorTest {

    @Test
    void minioHealthIndicator_reportsUpWhenBucketExists() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        KbProperties properties = new KbProperties();
        properties.getMinio().setRawBucket("kb-raw");
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        MinioHealthIndicator indicator = new MinioHealthIndicator(minioClient, properties);

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void minioHealthIndicator_reportsDownWhenBucketMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        KbProperties properties = new KbProperties();
        properties.getMinio().setRawBucket("kb-raw");
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        MinioHealthIndicator indicator = new MinioHealthIndicator(minioClient, properties);

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void minioHealthIndicator_reportsDownOnException() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        KbProperties properties = new KbProperties();
        properties.getMinio().setRawBucket("kb-raw");
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new RuntimeException("boom"));

        MinioHealthIndicator indicator = new MinioHealthIndicator(minioClient, properties);

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void ragflowHealthIndicator_canBeConstructed() {
        KbProperties properties = new KbProperties();
        properties.getRagflow().setBaseUrl("http://localhost:9380");
        properties.getRagflow().setTimeoutMs(1);

        RagflowHealthIndicator indicator = new RagflowHealthIndicator(WebClient.builder(), properties);

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
