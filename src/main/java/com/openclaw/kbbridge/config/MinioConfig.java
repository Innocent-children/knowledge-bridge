package com.openclaw.kbbridge.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置类。
 * <p>
 * 根据 {@link KbProperties.Minio} 中的 endpoint、accessKey、secretKey 创建 MinioClient
 * Bean。
 * </p>
 */
@Configuration
public class MinioConfig {

    /**
     * 创建 MinioClient Bean。
     *
     * @param kbProperties 统一配置
     * @return MinioClient 实例
     */
    @Bean
    public MinioClient minioClient(KbProperties kbProperties) {
        KbProperties.Minio minio = kbProperties.getMinio();
        return MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }
}
