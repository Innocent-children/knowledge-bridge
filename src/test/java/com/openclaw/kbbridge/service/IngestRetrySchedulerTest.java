package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IngestRetryScheduler 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class IngestRetrySchedulerTest {

        @Mock
        private IngestTaskMapper ingestTaskMapper;

        private KbProperties kbProperties;
        private IngestRetryScheduler scheduler;

        @BeforeEach
        void setUp() {
                kbProperties = new KbProperties();
                kbProperties.setIngest(new KbProperties.Ingest());
                kbProperties.getIngest().setRetryMaxAttempts(3);
                kbProperties.getIngest().setRetryDelayMs(5000);
                kbProperties.setRagflow(new KbProperties.Ragflow());
                kbProperties.getRagflow().setBaseUrl("test-dataset-id");
                kbProperties.setMinio(new KbProperties.Minio());
                kbProperties.getMinio().setProcessedBucket("kb-processed");

                scheduler = new IngestRetryScheduler(ingestTaskMapper, kbProperties);
        }

        /**
         * 无失败任务时不执行任何操作。
         */
        @Test
        void retryFailedTasks_noFailedTasks_doesNothing() {
                when(ingestTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                                .thenReturn(List.of());

                scheduler.retryFailedTasks();

                verify(ingestTaskMapper, never()).updateById(any(IngestTaskEntity.class));
        }

        /**
         * 重试成功时更新状态为 COMPLETED 并清除错误信息。
         */
        @Test
        void retrySingleTask_success_updatesStatusToCompleted() {
                IngestTaskEntity task = buildFailedTask(1L, "req-001", 0);
                task.setProcessedQaKey("qa/path/qa.md");

                scheduler.retrySingleTask(task, 3);

                ArgumentCaptor<IngestTaskEntity> captor = ArgumentCaptor.forClass(IngestTaskEntity.class);
                verify(ingestTaskMapper).updateById(captor.capture());

                IngestTaskEntity updated = captor.getValue();
                assertEquals(DocumentStatus.COMPLETED.name(), updated.getStatus());
                assertNull(updated.getErrorMessage());
                assertEquals(1, updated.getRetryCount());
        }

        /**
         * 已达最大重试次数时跳过。
         */
        @Test
        void retrySingleTask_maxRetriesReached_skips() {
                IngestTaskEntity task = buildFailedTask(3L, "req-003", 3);
                task.setProcessedQaKey("qa/path/qa.md");

                scheduler.retrySingleTask(task, 3);

                verify(ingestTaskMapper, never()).updateById(any(IngestTaskEntity.class));
        }

        /**
         * 同时有 Guide 和 Q&A 处理件时直接标记为 COMPLETED。
         */
        @Test
        void retrySingleTask_bothGuideAndQa_marksCompleted() {
                IngestTaskEntity task = buildFailedTask(4L, "req-004", 0);
                task.setProcessedGuideKey("guide/path/guide.md");
                task.setProcessedQaKey("qa/path/qa.md");

                scheduler.retrySingleTask(task, 3);

                verify(ingestTaskMapper).updateById(any(IngestTaskEntity.class));
                assertEquals(DocumentStatus.COMPLETED.name(), task.getStatus());
        }

        /**
         * retryCount 为 null 时视为 0。
         */
        @Test
        void retrySingleTask_nullRetryCount_treatsAsZero() {
                IngestTaskEntity task = buildFailedTask(5L, "req-005", null);
                task.setProcessedQaKey("qa/path/qa.md");

                scheduler.retrySingleTask(task, 3);

                assertEquals(DocumentStatus.COMPLETED.name(), task.getStatus());
                assertEquals(1, task.getRetryCount());
        }

        /**
         * 多个失败任务时逐个处理。
         */
        @Test
        void retryFailedTasks_multipleFailedTasks_processesEachIndependently() {
                IngestTaskEntity task1 = buildFailedTask(10L, "req-010", 0);
                task1.setProcessedQaKey("qa/path1/qa.md");

                IngestTaskEntity task2 = buildFailedTask(11L, "req-011", 1);
                task2.setProcessedQaKey("qa/path2/qa.md");

                when(ingestTaskMapper.selectList(any(LambdaQueryWrapper.class)))
                                .thenReturn(List.of(task1, task2));

                scheduler.retryFailedTasks();

                // 两个任务都应被更新
                verify(ingestTaskMapper, times(2)).updateById(any(IngestTaskEntity.class));

                // Both tasks should be COMPLETED
                assertEquals(DocumentStatus.COMPLETED.name(), task1.getStatus());
                assertEquals(DocumentStatus.COMPLETED.name(), task2.getStatus());
        }

        // ── 辅助方法 ──

        private IngestTaskEntity buildFailedTask(Long id, String requestId, Integer retryCount) {
                IngestTaskEntity task = new IngestTaskEntity();
                task.setId(id);
                task.setRequestId(requestId);
                task.setStatus(DocumentStatus.FAILED.name());
                task.setRetryCount(retryCount);
                task.setErrorMessage("入库失败");
                return task;
        }
}
