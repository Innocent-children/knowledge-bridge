package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MinioStorageClient 单元测试。
 * <p>
 * 验证路径生成逻辑和各 put 方法的正确性。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class MinioStorageClientTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    @Mock
    private MinioClient minioClient;
    private KbProperties kbProperties;
    private MinioStorageClient storageClient;

    @BeforeEach
    void setUp() {
        kbProperties = new KbProperties();
        KbProperties.Minio minio = new KbProperties.Minio();
        minio.setEndpoint("http://localhost:9000");
        minio.setAccessKey("testKey");
        minio.setSecretKey("testSecret");
        minio.setRawBucket("kb-raw");
        minio.setProcessedBucket("kb-processed");
        kbProperties.setMinio(minio);

        storageClient = new MinioStorageClient(minioClient, kbProperties);
    }

    // ── buildDatePath 路径格式测试 ──

    @Test
    @DisplayName("buildDatePath 应生成 {prefix}/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-{suffix}.md 格式路径")
    void buildDatePath_shouldGenerateCorrectFormat() {
        String path = MinioStorageClient.buildDatePath("markdown", "raw");
        String today = LocalDate.now().format(DATE_FMT);

        assertTrue(path.startsWith("markdown/" + today + "/"),
                "路径应以 prefix/日期/ 开头: " + path);
        assertTrue(path.endsWith("-raw.md"),
                "路径应以 -raw.md 结尾: " + path);
    }

    @Test
    @DisplayName("buildDatePath 日期部分应使用当天日期")
    void buildDatePath_shouldUseCurrentDate() {
        String path = MinioStorageClient.buildDatePath("guide", "guide");
        String today = LocalDate.now().format(DATE_FMT);
        assertTrue(path.contains(today), "路径应包含当天日期: " + today);
    }

    // ── putRawObject 测试 ──

    @Test
    @DisplayName("putRawObject 应返回正确格式的对象键并上传到 raw bucket")
    void putRawObject_shouldReturnCorrectKeyAndUploadToRawBucket() throws Exception {
        String key = storageClient.putRawObject("markdown", "# Hello");
        String today = LocalDate.now().format(DATE_FMT);

        assertTrue(key.startsWith("markdown/" + today + "/"));
        assertTrue(key.endsWith("-raw.md"));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("putRawObject 路径格式应匹配 {sourceType}/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-raw.md")
    void putRawObject_pathShouldMatchSpec() throws Exception {
        String key = storageClient.putRawObject("feishu_qa", "content");
        String today = LocalDate.now().format(DATE_FMT);

        assertTrue(key.startsWith("feishu_qa/"));
        assertTrue(key.contains(today));
        assertTrue(key.endsWith("-raw.md"));
    }

    // ── putProcessedGuide 测试 ──

    @Test
    @DisplayName("putProcessedGuide 应返回 guide/{date}/{timestamp}-guide.md 格式路径")
    void putProcessedGuide_shouldReturnCorrectKey() throws Exception {
        String key = storageClient.putProcessedGuide("# Guide content");
        String today = LocalDate.now().format(DATE_FMT);

        assertTrue(key.startsWith("guide/" + today + "/"));
        assertTrue(key.endsWith("-guide.md"));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    // ── putProcessedQa 测试 ──

    @Test
    @DisplayName("putProcessedQa 应返回 qa/{date}/{timestamp}-qa.md 格式路径")
    void putProcessedQa_shouldReturnCorrectKey() throws Exception {
        String key = storageClient.putProcessedQa("Q: What? A: This.");
        String today = LocalDate.now().format(DATE_FMT);

        assertTrue(key.startsWith("qa/" + today + "/"));
        assertTrue(key.endsWith("-qa.md"));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    // ── putFailedObject 测试 ──

    @Test
    @DisplayName("putFailedObject 应返回 failed/{date}/{timestamp}-failed.md 格式路径")
    void putFailedObject_shouldReturnCorrectKey() throws Exception {
        String key = storageClient.putFailedObject("Error details");
        String today = LocalDate.now().format(DATE_FMT);

        assertTrue(key.startsWith("failed/" + today + "/"));
        assertTrue(key.endsWith("-failed.md"));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    // ── 异常包装测试 ──

    @Test
    @DisplayName("putRawObject 在 MinIO 异常时应抛出 ExternalServiceException")
    void putRawObject_shouldWrapMinioException() throws Exception {
        doThrow(new RuntimeException("connection refused"))
                .when(minioClient).putObject(any(PutObjectArgs.class));

        ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                () -> storageClient.putRawObject("markdown", "content"));

        assertEquals("MinIO", ex.getServiceName());
        assertTrue(ex.getMessage().contains("MinIO 上传对象失败"));
    }

    // ── putObject bucket 验证 ──

    @Test
    @DisplayName("putRawObject 应上传到 rawBucket，putProcessedGuide 应上传到 processedBucket")
    void putMethods_shouldUseCorrectBuckets() throws Exception {
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);

        storageClient.putRawObject("md", "c1");
        verify(minioClient).putObject(captor.capture());
        assertEquals("kb-raw", captor.getValue().bucket());

        reset(minioClient);
        storageClient.putProcessedGuide("c2");
        verify(minioClient).putObject(captor.capture());
        assertEquals("kb-processed", captor.getValue().bucket());
    }
}
