package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled retry and recovery worker for ingest tasks.
 */
@Slf4j
@Component
public class IngestRetryScheduler {

    private final IngestTaskMapper ingestTaskMapper;
    private final KbProperties kbProperties;

    public IngestRetryScheduler(IngestTaskMapper ingestTaskMapper,
            KbProperties kbProperties) {
        this.ingestTaskMapper = ingestTaskMapper;
        this.kbProperties = kbProperties;
    }

    @Scheduled(fixedDelayString = "${kb.ingest.retry-delay-ms:5000}")
    public void retryFailedTasks() {
        int maxAttempts = kbProperties.getIngest().getRetryMaxAttempts();

        markOrphanedTasksFailed();

        List<IngestTaskEntity> failedTasks = ingestTaskMapper.selectList(
                baseRetryableQuery()
                        .eq(IngestTaskEntity::getStatus, DocumentStatus.FAILED.name())
                        .and(w -> w
                                .lt(IngestTaskEntity::getRetryCount, maxAttempts)
                                .or()
                                .isNull(IngestTaskEntity::getRetryCount)));

        if (failedTasks.isEmpty()) {
            return;
        }

        log.info("Retry scan found {} failed tasks", failedTasks.size());

        for (IngestTaskEntity task : failedTasks) {
            if (DocumentStatus.FAILED.name().equals(task.getStatus())) {
                retrySingleTask(task, maxAttempts);
            }
        }
    }

    private LambdaQueryWrapper<IngestTaskEntity> baseRetryableQuery() {
        return new LambdaQueryWrapper<IngestTaskEntity>()
                .isNull(IngestTaskEntity::getOperation)
                .and(w -> w
                        .isNotNull(IngestTaskEntity::getProcessedGuideKey)
                        .or()
                        .isNotNull(IngestTaskEntity::getProcessedQaKey));
    }

    private void markOrphanedTasksFailed() {
        LocalDateTime timeoutBefore = LocalDateTime.now()
                .minusNanos(kbProperties.getIngest().getOrphanTimeoutMs() * 1_000_000);

        List<IngestTaskEntity> orphanedTasks = ingestTaskMapper.selectList(
                new LambdaQueryWrapper<IngestTaskEntity>()
                        .isNull(IngestTaskEntity::getOperation)
                        .eq(IngestTaskEntity::getStatus,
                                DocumentStatus.PROCESSING.name())
                        .lt(IngestTaskEntity::getUpdatedAt, timeoutBefore));

        for (IngestTaskEntity task : orphanedTasks) {
            if (task.getOperation() != null || !DocumentStatus.PROCESSING.name().equals(task.getStatus())) {
                continue;
            }
            task.setStatus(DocumentStatus.FAILED.name());
            task.setErrorMessage("Task timed out and was marked failed for retry");
            task.setUpdatedAt(LocalDateTime.now());
            ingestTaskMapper.updateById(task);
        }

        if (!orphanedTasks.isEmpty()) {
            log.warn("Marked {} orphaned ingest tasks as FAILED", orphanedTasks.size());
        }
    }

    void retrySingleTask(IngestTaskEntity task, int maxAttempts) {
        if (task.getOperation() != null) return;
        int currentRetry = task.getRetryCount() != null ? task.getRetryCount() : 0;

        if (currentRetry >= maxAttempts) {
            log.warn("Task {} reached max retry attempts {}", task.getId(), maxAttempts);
            return;
        }

        log.info("Retrying ingest task taskId={}, retryCount={}/{}",
                task.getId(), currentRetry + 1, maxAttempts);

        try {
            // Processed files already exist in MinIO; RAGFlow polls MinIO to discover them.
            // Mark the task as COMPLETED directly.
            task.setStatus(DocumentStatus.COMPLETED.name());
            task.setErrorMessage(null);
            task.setRetryCount(currentRetry + 1);
            task.setUpdatedAt(LocalDateTime.now());
            ingestTaskMapper.updateById(task);

            log.info("Ingest retry succeeded: taskId={}", task.getId());

        } catch (Exception e) {
            task.setRetryCount(currentRetry + 1);
            task.setErrorMessage("补偿重试失败 (attempt " + (currentRetry + 1) + "): " + e.getMessage());
            task.setUpdatedAt(LocalDateTime.now());
            ingestTaskMapper.updateById(task);

            log.warn("Ingest retry failed: taskId={}, retryCount={}/{}, error={}",
                    task.getId(), currentRetry + 1, maxAttempts, e.getMessage());
        }
    }
}
