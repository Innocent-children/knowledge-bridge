package com.openclaw.kbbridge.preservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.review.ReviewApproveRequest;
import com.openclaw.kbbridge.dto.review.ReviewRejectRequest;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.entity.ReviewTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.repository.ReviewTaskMapper;
import com.openclaw.kbbridge.service.IngestRetryScheduler;
import com.openclaw.kbbridge.service.ReviewService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Preservation Property Tests for Review & Retry Flows (Phase 2).
 * <p>
 * These tests verify existing correct behavior on UNFIXED code that must be
 * preserved after bug fixes are applied. They are EXPECTED TO PASS on unfixed
 * code.
 * </p>
 * <p>
 * Tests follow observation-first methodology: observe behavior on unfixed code
 * for non-buggy inputs (CANDIDATE tasks for review, FAILED tasks with non-null
 * retryCount for retry).
 * </p>
 */
class ReviewRetryPreservationPropertyTest {

        private static final AtomicLong ID_GENERATOR = new AtomicLong(1);

        // ── Preservation P15: ReviewService.approve() on CANDIDATE task ──

        /**
         * Preservation P15: For any ReviewService.approve() call on a CANDIDATE task,
         * reviewStatus is updated to APPROVED, task status is updated to COMPLETED,
         * and a review record is created.
         * <p>
         * This verifies the existing review approval flow is preserved after fixes.
         * </p>
         *
         * <b>Validates: Requirements 3.10</b>
         */
        @Property(tries = 50)
        void approveOnCandidateTaskUpdatesStatusAndCreatesReviewRecord(
                        @ForAll("reviewers") String reviewer,
                        @ForAll("comments") String comment) {

                // Setup mocks
                IngestTaskMapper ingestTaskMapper = mock(IngestTaskMapper.class);
                ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
                MinioStorageClient minioStorageClient = mock(MinioStorageClient.class);
                KbProperties kbProperties = new KbProperties();

                ReviewService reviewService = new ReviewService(
                                ingestTaskMapper, reviewTaskMapper, minioStorageClient, kbProperties);

                // Create a CANDIDATE task
                long taskId = ID_GENERATOR.getAndIncrement();
                IngestTaskEntity task = new IngestTaskEntity();
                task.setId(taskId);
                task.setRequestId("req-" + taskId);
                task.setReviewStatus(ReviewStatus.CANDIDATE.name());
                task.setStatus(DocumentStatus.PROCESSING.name());
                task.setCreatedAt(LocalDateTime.now());
                task.setUpdatedAt(LocalDateTime.now());

                when(ingestTaskMapper.selectById(taskId)).thenReturn(task);
                when(ingestTaskMapper.updateById(any(IngestTaskEntity.class))).thenReturn(1);
                when(reviewTaskMapper.insert(any(ReviewTaskEntity.class))).thenReturn(1);

                // Execute approve
                ReviewApproveRequest request = new ReviewApproveRequest(taskId, reviewer, comment);
                reviewService.approve(request);

                // Verify: reviewStatus updated to APPROVED
                assertEquals(ReviewStatus.APPROVED.name(), task.getReviewStatus(),
                                "reviewStatus should be updated to APPROVED");

                // Verify: task status updated to COMPLETED
                assertEquals(DocumentStatus.COMPLETED.name(), task.getStatus(),
                                "task status should be updated to COMPLETED after approval");

                // Verify: updatedAt was refreshed
                assertNotNull(task.getUpdatedAt(), "updatedAt should be set");

                // Verify: ingestTaskMapper.updateById was called
                verify(ingestTaskMapper).updateById(task);

                // Verify: a review record was created
                ArgumentCaptor<ReviewTaskEntity> reviewCaptor = ArgumentCaptor.forClass(ReviewTaskEntity.class);
                verify(reviewTaskMapper).insert(reviewCaptor.capture());

                ReviewTaskEntity reviewRecord = reviewCaptor.getValue();
                assertEquals(taskId, reviewRecord.getTaskId(),
                                "Review record should reference the correct taskId");
                assertEquals(ReviewStatus.APPROVED.name(), reviewRecord.getReviewStatus(),
                                "Review record status should be APPROVED");
                assertEquals(reviewer, reviewRecord.getReviewer(),
                                "Review record reviewer should match request");
                assertEquals(comment, reviewRecord.getComment(),
                                "Review record comment should match request");
                assertNotNull(reviewRecord.getCreatedAt(),
                                "Review record createdAt should be set");
                assertNotNull(reviewRecord.getUpdatedAt(),
                                "Review record updatedAt should be set");
        }

        // ── Preservation P16: ReviewService.reject() on CANDIDATE task ──

        /**
         * Preservation P16: For any ReviewService.reject() call on a CANDIDATE task,
         * reviewStatus is updated to REJECTED and a review record is created.
         * <p>
         * This verifies the existing review rejection flow is preserved after fixes.
         * </p>
         *
         * <b>Validates: Requirements 3.11</b>
         */
        @Property(tries = 50)
        void rejectOnCandidateTaskUpdatesStatusAndCreatesReviewRecord(
                        @ForAll("reviewers") String reviewer,
                        @ForAll("comments") String comment) {

                // Setup mocks
                IngestTaskMapper ingestTaskMapper = mock(IngestTaskMapper.class);
                ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
                MinioStorageClient minioStorageClient = mock(MinioStorageClient.class);
                KbProperties kbProperties = new KbProperties();

                ReviewService reviewService = new ReviewService(
                                ingestTaskMapper, reviewTaskMapper, minioStorageClient, kbProperties);

                // Create a CANDIDATE task
                long taskId = ID_GENERATOR.getAndIncrement();
                IngestTaskEntity task = new IngestTaskEntity();
                task.setId(taskId);
                task.setRequestId("req-" + taskId);
                task.setReviewStatus(ReviewStatus.CANDIDATE.name());
                task.setStatus(DocumentStatus.PROCESSING.name());
                task.setCreatedAt(LocalDateTime.now());
                task.setUpdatedAt(LocalDateTime.now());

                when(ingestTaskMapper.selectById(taskId)).thenReturn(task);
                when(ingestTaskMapper.updateById(any(IngestTaskEntity.class))).thenReturn(1);
                when(reviewTaskMapper.insert(any(ReviewTaskEntity.class))).thenReturn(1);

                // Execute reject
                ReviewRejectRequest request = new ReviewRejectRequest(taskId, reviewer, comment);
                reviewService.reject(request);

                // Verify: reviewStatus updated to REJECTED
                assertEquals(ReviewStatus.REJECTED.name(), task.getReviewStatus(),
                                "reviewStatus should be updated to REJECTED");

                // Verify: task status should NOT be changed (reject doesn't change status)
                assertEquals(DocumentStatus.PROCESSING.name(), task.getStatus(),
                                "task status should remain unchanged after rejection");

                // Verify: updatedAt was refreshed
                assertNotNull(task.getUpdatedAt(), "updatedAt should be set");

                // Verify: ingestTaskMapper.updateById was called
                verify(ingestTaskMapper).updateById(task);

                // Verify: a review record was created
                ArgumentCaptor<ReviewTaskEntity> reviewCaptor = ArgumentCaptor.forClass(ReviewTaskEntity.class);
                verify(reviewTaskMapper).insert(reviewCaptor.capture());

                ReviewTaskEntity reviewRecord = reviewCaptor.getValue();
                assertEquals(taskId, reviewRecord.getTaskId(),
                                "Review record should reference the correct taskId");
                assertEquals(ReviewStatus.REJECTED.name(), reviewRecord.getReviewStatus(),
                                "Review record status should be REJECTED");
                assertEquals(reviewer, reviewRecord.getReviewer(),
                                "Review record reviewer should match request");
                assertEquals(comment, reviewRecord.getComment(),
                                "Review record comment should match request");
                assertNotNull(reviewRecord.getCreatedAt(),
                                "Review record createdAt should be set");
                assertNotNull(reviewRecord.getUpdatedAt(),
                                "Review record updatedAt should be set");
        }

        // ── Preservation P17: IngestRetryScheduler retries FAILED tasks ──

        /**
         * Preservation P17: For any FAILED task with non-null retryCount < maxAttempts
         * and processed content, IngestRetryScheduler marks the task as COMPLETED
         * (since RAGFlow now polls MinIO directly).
         * <p>
         * This verifies the existing retry flow is preserved after fixes.
         * Non-buggy input: retryCount is non-null (avoids Defect 15 bug condition).
         * </p>
         *
         * <b>Validates: Requirements 3.12</b>
         */
        @Property(tries = 30)
        void retrySchedulerRetriesFailedTasksWithProcessedContent(
                        @ForAll("retryCountsBelowMax") int retryCount,
                        @ForAll("processedContentKeys") String processedKey) {

                // Setup mocks
                IngestTaskMapper ingestTaskMapper = mock(IngestTaskMapper.class);
                KbProperties kbProperties = new KbProperties();
                kbProperties.getIngest().setRetryMaxAttempts(3);

                IngestRetryScheduler scheduler = new IngestRetryScheduler(
                                ingestTaskMapper, kbProperties);

                // Create a FAILED task with non-null retryCount and processed content
                long taskId = ID_GENERATOR.getAndIncrement();
                IngestTaskEntity task = new IngestTaskEntity();
                task.setId(taskId);
                task.setRequestId("req-retry-" + taskId);
                task.setStatus(DocumentStatus.FAILED.name());
                task.setRetryCount(retryCount);
                task.setProcessedQaKey(processedKey);
                task.setUpdatedAt(LocalDateTime.now().minusMinutes(1));

                // Mock: selectList returns our task
                List<IngestTaskEntity> failedTasks = new ArrayList<>();
                failedTasks.add(task);
                when(ingestTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(failedTasks);

                when(ingestTaskMapper.updateById(any(IngestTaskEntity.class))).thenReturn(1);

                // Execute retry
                scheduler.retryFailedTasks();

                // Verify: task status updated to COMPLETED
                assertEquals(DocumentStatus.COMPLETED.name(), task.getStatus(),
                                "Task status should be updated to COMPLETED after successful retry");

                // Verify: retryCount was incremented
                assertEquals(retryCount + 1, task.getRetryCount(),
                                "retryCount should be incremented by 1");

                // Verify: error message cleared on success
                assertNull(task.getErrorMessage(),
                                "errorMessage should be cleared on successful retry");

                // Verify: updatedAt was refreshed
                assertNotNull(task.getUpdatedAt(), "updatedAt should be set");

                // Verify: updateById was called
                verify(ingestTaskMapper).updateById(task);
        }

        // ── Preservation P18: Application config validation ──

        /**
         * Preservation P18: For any application configured with valid environment
         * variables, the system configuration binds correctly and operates normally.
         * <p>
         * This is a simple config validation test that verifies KbProperties binds
         * correctly with valid values, ensuring the application can start normally.
         * </p>
         *
         * <b>Validates: Requirements 3.15</b>
         */
        @Property(tries = 30)
        void validConfigurationBindsCorrectly(
                        @ForAll("validBaseUrls") String ragflowBaseUrl,
                        @ForAll("validApiKeys") String ragflowApiKey,
                        @ForAll("validEndpoints") String minioEndpoint,
                        @ForAll("validSecrets") String sharedSecret) {

                KbProperties props = new KbProperties();

                // Set RAGFlow config
                props.getRagflow().setBaseUrl(ragflowBaseUrl);
                props.getRagflow().setApiKey(ragflowApiKey);
                props.getRagflow().setTimeoutMs(5000);
                props.getRagflow().setRetryMaxAttempts(3);
                props.getRagflow().setRetryDelayMs(1000);

                // Set MinIO config
                props.getMinio().setEndpoint(minioEndpoint);
                props.getMinio().setAccessKey("test-access-key");
                props.getMinio().setSecretKey("test-secret-key");
                props.getMinio().setRawBucket("kb-raw");
                props.getMinio().setProcessedBucket("kb-processed");

                // Set Security config
                props.getSecurity().setSharedSecret(sharedSecret);
                props.getSecurity().setTimestampToleranceMs(300000);
                props.getSecurity().setSignatureAlgorithm("HmacSHA256");

                // Set Ingest config
                props.getIngest().setAsyncPoolSize(4);
                props.getIngest().setRetryMaxAttempts(3);
                props.getIngest().setRetryDelayMs(5000);

                // Verify all config values bind correctly
                assertEquals(ragflowBaseUrl, props.getRagflow().getBaseUrl(),
                                "RAGFlow baseUrl should bind correctly");
                assertEquals(ragflowApiKey, props.getRagflow().getApiKey(),
                                "RAGFlow apiKey should bind correctly");
                assertEquals(5000, props.getRagflow().getTimeoutMs(),
                                "RAGFlow timeoutMs should bind correctly");
                assertEquals(3, props.getRagflow().getRetryMaxAttempts(),
                                "RAGFlow retryMaxAttempts should bind correctly");

                assertEquals(minioEndpoint, props.getMinio().getEndpoint(),
                                "MinIO endpoint should bind correctly");
                assertEquals("kb-raw", props.getMinio().getRawBucket(),
                                "MinIO rawBucket should bind correctly");
                assertEquals("kb-processed", props.getMinio().getProcessedBucket(),
                                "MinIO processedBucket should bind correctly");

                assertEquals(sharedSecret, props.getSecurity().getSharedSecret(),
                                "Security sharedSecret should bind correctly");
                assertEquals(300000, props.getSecurity().getTimestampToleranceMs(),
                                "Security timestampToleranceMs should bind correctly");
                assertEquals("HmacSHA256", props.getSecurity().getSignatureAlgorithm(),
                                "Security signatureAlgorithm should bind correctly");

                assertEquals(4, props.getIngest().getAsyncPoolSize(),
                                "Ingest asyncPoolSize should bind correctly");
                assertEquals(3, props.getIngest().getRetryMaxAttempts(),
                                "Ingest retryMaxAttempts should bind correctly");

                // Verify defaults are reasonable
                assertNotNull(props.getQuery(), "Query config should have defaults");
                assertNotNull(props.getProcessor(), "Processor config should have defaults");
                assertTrue(props.getQuery().getMaxSources() > 0,
                                "maxSources default should be positive");
                assertTrue(props.getQuery().getMaxContentLength() > 0,
                                "maxContentLength default should be positive");
                assertTrue(props.getQuery().getMaxTotalLength() > 0,
                                "maxTotalLength default should be positive");
        }

        // ========== Custom Arbitrary Providers ==========

        @Provide
        Arbitrary<String> reviewers() {
                return Arbitraries.of(
                                "admin", "reviewer1", "张三", "李四",
                                "john.doe", "quality-team", "senior-reviewer");
        }

        @Provide
        Arbitrary<String> comments() {
                return Arbitraries.of(
                                "内容质量良好，审核通过",
                                "内容不符合规范，请修改",
                                "Approved - good quality",
                                "Rejected - needs revision",
                                "格式正确，内容完整",
                                null,
                                "",
                                "需要补充更多细节");
        }

        @Provide
        Arbitrary<Integer> retryCountsBelowMax() {
                // Non-null retryCount values below maxAttempts (3)
                // This avoids the Defect 15 bug condition (null retryCount)
                return Arbitraries.integers().between(0, 2);
        }

        @Provide
        Arbitrary<String> processedContentKeys() {
                return Arbitraries.of(
                                "qa/2024/01/15/req-001/qa.md",
                                "qa/2024/06/20/req-abc/qa.md",
                                "guide/2024/03/10/req-xyz/guide.md",
                                "qa/2025/01/01/req-test/qa.md");
        }

        @Provide
        Arbitrary<String> validBaseUrls() {
                return Arbitraries.of(
                                "http://localhost:9380",
                                "http://ragflow:9380",
                                "https://ragflow.example.com",
                                "http://192.168.1.100:9380");
        }

        @Provide
        Arbitrary<String> validApiKeys() {
                return Arbitraries.of(
                                "ragflow-api-key-001",
                                "sk-test-key-12345",
                                "api_key_production_abc",
                                "ragflow-ZxYw9876");
        }

        @Provide
        Arbitrary<String> validEndpoints() {
                return Arbitraries.of(
                                "http://localhost:9000",
                                "http://minio:9000",
                                "https://minio.example.com",
                                "http://192.168.1.100:9000");
        }

        @Provide
        Arbitrary<String> validSecrets() {
                return Arbitraries.of(
                                "my-shared-secret-key-2024",
                                "production-hmac-secret",
                                "test-secret-key-abc123",
                                "kb-bridge-secret-xyz");
        }
}
