package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MinIO 存储客户端。
 * <p>
 * 封装对 MinIO 的所有对象存储操作，包括原始件、Guide 处理件、Q&A 处理件、失败件的存取。
 * 所有 MinIO 操作异常统一包装为 {@link ExternalServiceException}。
 * </p>
 */
@Component
public class MinioStorageClient {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageClient.class);
    private static final String SERVICE_NAME = "MinIO";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final MinioClient minioClient;
    private final KbProperties kbProperties;

    public MinioStorageClient(MinioClient minioClient, KbProperties kbProperties) {
        this.minioClient = minioClient;
        this.kbProperties = kbProperties;
    }

    /**
     * 构建基于日期的存储路径。
     * <p>
     * 格式：{prefix}/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-{suffix}.md
     * </p>
     *
     * @param prefix 路径前缀（如 sourceType 或 "guide"）
     * @param suffix 文件后缀标识（如 "raw"、"guide"、"qa"、"failed"）
     * @return 完整的对象键
     */
    static String buildDatePath(String prefix, String suffix) {
        LocalDateTime now = LocalDateTime.now();
        String datePart = now.format(DATE_FORMATTER);
        String filePart = now.format(FILE_FORMATTER);
        return prefix + "/" + datePart + "/" + filePart + "-" + suffix + ".md";
    }

    /**
     * 构建基于内容主题的存储路径。
     * <p>
     * 格式：{prefix}/{yyyy-MM-dd}/{sanitizedTopic}-{suffix}.md
     * 如果 topic 为空或无效，回退到日期格式。
     * </p>
     *
     * @param prefix 路径前缀（如 "guide"、"qa"）
     * @param suffix 文件后缀标识（如 "guide"、"qa"）
     * @param topic  LLM 生成的文件主题名称
     * @return 完整的对象键
     */
    static String buildTopicPath(String prefix, String suffix, String topic) {
        if (topic == null || topic.isBlank()) {
            return buildDatePath(prefix, suffix);
        }
        LocalDateTime now = LocalDateTime.now();
        String datePart = now.format(DATE_FORMATTER);
        String sanitized = sanitizeFileName(topic);
        if (sanitized.isEmpty()) {
            return buildDatePath(prefix, suffix);
        }
        return prefix + "/" + datePart + "/" + sanitized + "-" + suffix + ".md";
    }

    /**
     * 将主题文本转换为安全的文件名。
     * <p>
     * 规则：
     * <ul>
     * <li>移除文件系统不允许的字符（/ \ : * ? " &lt; &gt; |）</li>
     * <li>将空格替换为下划线</li>
     * <li>截断到最大 80 个字符（避免路径过长）</li>
     * <li>去除首尾空白和点号</li>
     * </ul>
     * </p>
     *
     * @param topic 原始主题文本
     * @return 安全的文件名字符串
     */
    static String sanitizeFileName(String topic) {
        if (topic == null || topic.isBlank()) {
            return "";
        }
        // 移除文件系统不允许的字符
        String sanitized = topic.replaceAll("[/\\\\:*?\"<>|]", "");
        // 将空格替换为下划线
        sanitized = sanitized.replaceAll("\\s+", "_");
        // 去除首尾点号和空白
        sanitized = sanitized.replaceAll("^[.\\s]+|[.\\s]+$", "");
        // 截断到 80 个字符
        if (sanitized.length() > 80) {
            sanitized = sanitized.substring(0, 80);
            // 避免截断在下划线处留下尾部下划线
            sanitized = sanitized.replaceAll("_+$", "");
        }
        return sanitized;
    }

    /**
     * 保存原始件到 kb-raw 桶。
     * <p>
     * 路径格式：{sourceType}/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-raw.md
     * </p>
     *
     * @param sourceType 来源类型
     * @param content    原始内容
     * @return 对象键（object key）
     */
    public String putRawObject(String sourceType, String content) {
        String objectKey = buildDatePath(sourceType, "raw");
        putObject(kbProperties.getMinio().getRawBucket(), objectKey, content);
        log.info("原始件已保存: bucket={}, key={}", kbProperties.getMinio().getRawBucket(), objectKey);
        return objectKey;
    }

    /**
     * 保存 Guide 处理件到 kb-processed 桶。
     * <p>
     * 路径格式：guide/{yyyy-MM-dd}/{topic}-guide.md（topic 由 LLM 决定）
     * 如果 topic 为空，回退到日期格式：guide/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-guide.md
     * </p>
     *
     * @param content Guide 内容
     * @param topic   LLM 生成的文件主题名称（可空）
     * @return 对象键（object key）
     */
    public String putProcessedGuide(String content, String topic) {
        String objectKey = buildTopicPath("guide", "guide", topic);
        putObject(kbProperties.getMinio().getProcessedBucket(), objectKey, content);
        log.info("Guide 处理件已保存: bucket={}, key={}", kbProperties.getMinio().getProcessedBucket(), objectKey);
        return objectKey;
    }

    /**
     * 保存 Guide 处理件到 kb-processed 桶（无主题，使用日期命名）。
     *
     * @param content Guide 内容
     * @return 对象键（object key）
     */
    public String putProcessedGuide(String content) {
        return putProcessedGuide(content, null);
    }

    /**
     * 保存 Q&A 处理件到 kb-processed 桶。
     * <p>
     * 路径格式：qa/{yyyy-MM-dd}/{topic}-qa.md（topic 由 LLM 决定）
     * 如果 topic 为空，回退到日期格式：qa/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-qa.md
     * </p>
     *
     * @param content Q&A 内容
     * @param topic   LLM 生成的文件主题名称（可空）
     * @return 对象键（object key）
     */
    public String putProcessedQa(String content, String topic) {
        String objectKey = buildTopicPath("qa", "qa", topic);
        putObject(kbProperties.getMinio().getProcessedBucket(), objectKey, content);
        log.info("Q&A 处理件已保存: bucket={}, key={}", kbProperties.getMinio().getProcessedBucket(), objectKey);
        return objectKey;
    }

    /**
     * 保存 Q&A 处理件到 kb-processed 桶（无主题，使用日期命名）。
     *
     * @param content Q&A 内容
     * @return 对象键（object key）
     */
    public String putProcessedQa(String content) {
        return putProcessedQa(content, null);
    }

    /**
     * 保存失败件到 kb-processed 桶。
     * <p>
     * 路径格式：failed/{yyyy-MM-dd}/{yyyy-MM-dd-HHmmss}-failed.md
     * </p>
     *
     * @param content 失败内容
     * @return 对象键（object key）
     */
    public String putFailedObject(String content) {
        String objectKey = buildDatePath("failed", "failed");
        putObject(kbProperties.getMinio().getProcessedBucket(), objectKey, content);
        log.info("失败件已保存: bucket={}, key={}", kbProperties.getMinio().getProcessedBucket(), objectKey);
        return objectKey;
    }

    /**
     * 读取对象内容（UTF-8 字符串）。
     *
     * @param bucket    桶名称
     * @param objectKey 对象键
     * @return 对象内容字符串
     */
    public String getObject(String bucket, String objectKey) {
        try (var response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .build())) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ExternalServiceException(
                    "MinIO 读取对象失败: bucket=" + bucket + ", key=" + objectKey,
                    null, SERVICE_NAME, null, e);
        }
    }

    // ── 内部方法 ──

    /**
     * 检查对象是否存在。
     *
     * @param bucket    桶名称
     * @param objectKey 对象键
     * @return 对象存在返回 true，否则返回 false
     */
    public boolean exists(String bucket, String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
            return true;
        } catch (io.minio.errors.ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new ExternalServiceException(
                    "MinIO 检查对象存在性失败: bucket=" + bucket + ", key=" + objectKey,
                    null, SERVICE_NAME, null, e);
        } catch (Exception e) {
            throw new ExternalServiceException(
                    "MinIO 检查对象存在性失败: bucket=" + bucket + ", key=" + objectKey,
                    null, SERVICE_NAME, null, e);
        }
    }

    /**
     * 上传内容到 MinIO。
     *
     * @param bucket    桶名称
     * @param objectKey 对象键
     * @param content   内容字符串
     */
    private void putObject(String bucket, String objectKey, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L)
                            .contentType("text/markdown; charset=utf-8")
                            .build());
        } catch (Exception e) {
            throw new ExternalServiceException(
                    "MinIO 上传对象失败: bucket=" + bucket + ", key=" + objectKey,
                    null, SERVICE_NAME, null, e);
        }
    }
}
