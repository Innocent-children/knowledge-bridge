package com.openclaw.kbbridge.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for MinIO connectivity.
 */
@Component
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient minioClient;
    private final KbProperties.Minio minioConfig;

    public MinioHealthIndicator(MinioClient minioClient, KbProperties kbProperties) {
        this.minioClient = minioClient;
        this.minioConfig = kbProperties.getMinio();
    }

    @Override
    public Health health() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(minioConfig.getRawBucket())
                            .build());
            Health.Builder builder = exists ? Health.up() : Health.down();
            return builder
                    .withDetail("bucket", minioConfig.getRawBucket())
                    .withDetail("endpoint", minioConfig.getEndpoint())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("bucket", minioConfig.getRawBucket())
                    .withDetail("endpoint", minioConfig.getEndpoint())
                    .build();
        }
    }
}
