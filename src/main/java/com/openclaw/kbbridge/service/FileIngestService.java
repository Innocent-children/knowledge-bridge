package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ingest.FileIngestResponse;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.exception.ValidationException;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class FileIngestService {
    private final IngestTaskMapper taskMapper;
    private final MinioStorageClient storage;
    private final FileIngestWorker worker;
    private final KbProperties properties;

    public FileIngestService(IngestTaskMapper taskMapper, MinioStorageClient storage,
                             FileIngestWorker worker, KbProperties properties) {
        this.taskMapper = taskMapper;
        this.storage = storage;
        this.worker = worker;
        this.properties = properties;
    }

    public FileIngestResponse ingest(String requestId, String userId, String chatId,
                                     String messageId, String expectedSha256,
                                     boolean force, MultipartFile file) {
        IngestTaskEntity existing = findByRequestId(requestId);
        if (existing != null) {
            return response(existing, true);
        }
        validate(requestId, file);
        String hash = sha256(file);
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !hash.equalsIgnoreCase(expectedSha256)) {
            throw new ValidationException("文件哈希校验失败", requestId, "sha256");
        }

        LocalDateTime now = LocalDateTime.now();
        IngestTaskEntity task = new IngestTaskEntity();
        task.setRequestId(requestId);
        task.setSourceChannel("feishu");
        task.setSourceType("ATTACHMENT");
        task.setUserId(userId);
        task.setChatId(chatId);
        task.setSourceMessageId(messageId);
        task.setStatus(DocumentStatus.RECEIVED.name());
        task.setReviewStatus(ReviewStatus.CANDIDATE.name());
        task.setContentHash(hash);
        task.setFileName(file.getOriginalFilename());
        task.setMimeType(file.getContentType());
        task.setFileSize(file.getSize());
        task.setRetryCount(0);
        task.setRetryable(false);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            IngestTaskEntity raced = findByRequestId(requestId);
            if (raced != null) {
                return response(raced, true);
            }
            throw e;
        }
        try (InputStream input = file.getInputStream()) {
            String key = storage.putRawFile(task.getId(), task.getFileName(), task.getMimeType(),
                    input, task.getFileSize());
            task.setRawObjectKey(key);
            task.setStatus(DocumentStatus.RAW_STORED.name());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        } catch (Exception e) {
            task.setStatus(DocumentStatus.FAILED.name());
            task.setErrorCode("STORAGE_FAILED");
            task.setErrorMessage("原始文件保存失败");
            task.setRetryable(true);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            throw new IllegalStateException("原始文件保存失败", e);
        }
        worker.processAsync(task.getId());
        return response(task, false);
    }

    private void validate(String requestId, MultipartFile file) {
        if (requestId == null || requestId.isBlank()) {
            throw new ValidationException("requestId不能为空", requestId, "requestId");
        }
        if (file == null || file.isEmpty()) {
            throw new ValidationException("必须上传一个非空文件", requestId, "file");
        }
        if (file.getSize() > properties.getIngest().getMaxFileSizeBytes()) {
            throw new ValidationException("文件超过大小限制", requestId, "file");
        }
        String extension = FileTextExtractor.extension(file.getOriginalFilename());
        if (!properties.getIngest().getSupportedFileExtensions().contains(extension)) {
            throw new ValidationException("不支持的文件格式", requestId, "file");
        }
    }

    private String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("无法计算文件哈希", e);
        }
    }

    private IngestTaskEntity findByRequestId(String requestId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<IngestTaskEntity>()
                .eq(IngestTaskEntity::getRequestId, requestId));
    }

    private FileIngestResponse response(IngestTaskEntity task, boolean duplicate) {
        return new FileIngestResponse(task.getRequestId(), task.getId(), task.getStatus(), duplicate);
    }
}
