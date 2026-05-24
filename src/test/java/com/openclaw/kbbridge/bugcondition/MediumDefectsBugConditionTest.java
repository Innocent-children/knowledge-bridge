package com.openclaw.kbbridge.bugcondition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.builder.EvidencePackBuilder;
import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.client.RagflowClient;
import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ingest.IngestRequest;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.dto.ragflow.RetrievalRequest;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.entity.QueryLogEntity;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.exception.ValidationException;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.model.enums.QueryStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.repository.QueryLogMapper;
import com.openclaw.kbbridge.router.QueryRouter;
import com.openclaw.kbbridge.security.CachedBodyHttpServletRequest;
import com.openclaw.kbbridge.service.*;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Bug Condition Exploration Tests for Medium Priority Defects (10, 11, 12, 13,
 * 15).
 * <p>
 * These tests encode the EXPECTED (correct) behavior. On unfixed code, they are
 * EXPECTED TO FAIL, confirming the bugs exist.
 * </p>
 *
 * <b>Validates: Requirements 1.10, 1.11, 1.12, 1.13, 1.15</b>
 */
class MediumDefectsBugConditionTest {

    // ── Defect 10: Orphaned Tasks Not Retried ──

    /**
     * Defect 10 - Orphaned Tasks Not Retried:
     * Create tasks with status=PROCESSING and updatedAt older than 10 minutes.
     * Run retryFailedTasks(). Verify these orphaned tasks are included in the
     * retry scan.
     * <p>
     * On unfixed code, only status=FAILED is scanned by retryFailedTasks(),
     * so orphaned PROCESSING tasks are never retried.
     * Expected counterexample: orphaned PROCESSING task is not retried.
     * </p>
     *
     * <b>Validates: Requirements 2.10</b>
     */
    @Example
    void orphanedProcessingTasksShouldBeIncludedInRetryScan() {
        IngestTaskMapper mapper = mock(IngestTaskMapper.class);
        RagflowClient ragflowClient = mock(RagflowClient.class);
        MinioStorageClient minioClient = mock(MinioStorageClient.class);
        KbProperties kbProperties = createKbProperties();

        // Create an orphaned task: status=PROCESSING, updatedAt 15 minutes ago
        IngestTaskEntity orphanedTask = new IngestTaskEntity();
        orphanedTask.setId(1L);
        orphanedTask.setRequestId("req-orphan-1");
        orphanedTask.setStatus(DocumentStatus.PROCESSING.name());
        orphanedTask.setUpdatedAt(LocalDateTime.now().minusMinutes(15));
        orphanedTask.setRetryCount(0);
        orphanedTask.setProcessedGuideKey("guide/key");

        // Track all queries made to the mapper
        List<LambdaQueryWrapper<IngestTaskEntity>> capturedQueries = new ArrayList<>();

        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaQueryWrapper<IngestTaskEntity> wrapper = invocation.getArgument(0);
            capturedQueries.add(wrapper);
            // Return empty list for all queries (we're just checking if the query is made)
            return List.of();
        });

        IngestRetryScheduler scheduler = new IngestRetryScheduler(
                mapper, kbProperties);

        scheduler.retryFailedTasks();

        // On FIXED code: retryFailedTasks() should make at least 2 queries:
        // 1. For FAILED tasks (existing behavior)
        // 2. For orphaned PROCESSING/SYNC_PENDING tasks with old updatedAt
        // On UNFIXED code: only 1 query is made (for FAILED tasks only)
        assertTrue(capturedQueries.size() >= 2,
                "retryFailedTasks() should query for both FAILED tasks AND orphaned "
                        + "PROCESSING/SYNC_PENDING tasks, but only made "
                        + capturedQueries.size() + " query(ies). "
                        + "Orphaned tasks stuck in PROCESSING status after a crash will never be retried.");
    }

    // ── Defect 11: handleDegradation Status Overwrite ──

    /**
     * Defect 11 - handleDegradation Status Overwrite:
     * Trigger handleDegradation() with a mock ExternalServiceException, then
     * verify the persisted QueryLogEntity.status is ANSWER_FAILED.
     * <p>
     * On unfixed code, saveSuccessResult() overwrites status to ANSWERED after
     * handleDegradation() sets it to ANSWER_FAILED.
     * Expected counterexample: status is "ANSWERED" instead of "ANSWER_FAILED".
     * </p>
     *
     * <b>Validates: Requirements 2.11</b>
     */
    @Example
    void handleDegradationShouldPreserveAnswerFailedStatus() {
        QueryRouter queryRouter = mock(QueryRouter.class);
        RagflowClient ragflowClient = mock(RagflowClient.class);
        EvidencePackBuilder evidencePackBuilder = mock(EvidencePackBuilder.class);
        QueryLogMapper queryLogMapper = mock(QueryLogMapper.class);
        KbProperties kbProperties = createKbProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        KbMetrics kbMetrics = mock(KbMetrics.class);

        // Setup: route to KB_PLUS_LLM so RAGFlow is called
        when(queryRouter.route(any(QueryRequest.class))).thenReturn(QueryRoute.KB_PLUS_LLM);

        // Setup: RAGFlow throws ExternalServiceException (triggers degradation)
        when(ragflowClient.retrieval(any(RetrievalRequest.class), anyString()))
                .thenThrow(new ExternalServiceException(
                        "RAGFlow connection timeout", "req-degrade-1", "RAGFlow", 503));

        // Capture the QueryLogEntity updates
        ArgumentCaptor<QueryLogEntity> logCaptor = ArgumentCaptor.forClass(QueryLogEntity.class);
        when(queryLogMapper.insert(any(QueryLogEntity.class))).thenReturn(1);
        when(queryLogMapper.updateById(logCaptor.capture())).thenReturn(1);

        QueryService queryService = new QueryService(
                queryRouter, ragflowClient, evidencePackBuilder,
                queryLogMapper, kbProperties, objectMapper, kbMetrics);

        QueryRequest request = new QueryRequest(
                "req-degrade-1", "user1", null, null, null,
                "test question", null, false, null);

        // Execute query - should trigger degradation path
        QueryResponse response = queryService.query(request);

        // Get the last persisted state of the query log
        List<QueryLogEntity> capturedLogs = logCaptor.getAllValues();
        assertFalse(capturedLogs.isEmpty(), "QueryLogEntity should have been updated");

        QueryLogEntity lastUpdate = capturedLogs.getLast();

        // On FIXED code: status should be ANSWER_FAILED (degradation status preserved)
        // On UNFIXED code: status is ANSWERED (saveSuccessResult overwrites it)
        assertEquals(QueryStatus.ANSWER_FAILED.name(), lastUpdate.getStatus(),
                "After handleDegradation(), the persisted query log status should be "
                        + "ANSWER_FAILED, but was: " + lastUpdate.getStatus()
                        + ". saveSuccessResult() is overwriting the degradation status to ANSWERED.");
    }

    // ── Defect 12: Unbounded Request Body ──

    /**
     * Defect 12 - Unbounded Request Body:
     * Create a CachedBodyHttpServletRequest with a request body larger than 1 MB.
     * <p>
     * On unfixed code, readAllBytes() has no size limit, so it succeeds for any
     * size. After fix, it should reject with an exception for bodies > 1 MB.
     * Expected counterexample: no size limit enforced, large body accepted.
     * </p>
     *
     * <b>Validates: Requirements 2.12</b>
     */
    @Property(tries = 5)
    void oversizedRequestBodyShouldBeRejected(
            @ForAll("oversizedBodySizes") int bodySize) throws Exception {

        // Create a mock HttpServletRequest with an oversized body
        byte[] oversizedBody = new byte[bodySize];
        // Fill with some data
        for (int i = 0; i < oversizedBody.length; i++) {
            oversizedBody[i] = (byte) ('A' + (i % 26));
        }

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        ByteArrayInputStream bais = new ByteArrayInputStream(oversizedBody);
        ServletInputStream servletInputStream = new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }

            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return bais.read(b, off, len);
            }
        };
        when(mockRequest.getInputStream()).thenReturn(servletInputStream);

        // On FIXED code: constructor should throw IOException for body > 1 MB
        // On UNFIXED code: readAllBytes() succeeds with no limit
        boolean exceptionThrown = false;
        try {
            new CachedBodyHttpServletRequest(mockRequest);
        } catch (IOException e) {
            exceptionThrown = true;
        }

        assertTrue(exceptionThrown,
                "CachedBodyHttpServletRequest should reject request bodies larger than 1 MB "
                        + "(body size: " + bodySize + " bytes), but no exception was thrown. "
                        + "readAllBytes() has no size limit, risking OutOfMemoryError.");
    }

    // ── Defect 13: Invalid sourceType Not Rejected ──

    /**
     * Defect 13 - Invalid sourceType Not Rejected:
     * Call IngestService.createTask() with sourceType="UNKNOWN_TYPE".
     * <p>
     * On unfixed code, IngestRouter silently falls back to FeishuQaProcessor
     * for unknown sourceTypes. After fix, it should throw ValidationException.
     * Expected counterexample: no validation error, request processed with wrong
     * processor.
     * </p>
     *
     * <b>Validates: Requirements 2.13</b>
     */
    @Property(tries = 10)
    void invalidSourceTypeShouldBeRejected(
            @ForAll("invalidSourceTypes") String invalidSourceType) {

        IngestTaskMapper mapper = mock(IngestTaskMapper.class);
        IngestAsyncWorker asyncWorker = mock(IngestAsyncWorker.class);
        DocumentService documentService = mock(DocumentService.class);

        // Setup mapper: no existing task (new request)
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        IngestService service = new IngestService(mapper, asyncWorker, documentService);

        IngestRequest request = new IngestRequest(
                "req-invalid-type-" + invalidSourceType,
                "user1", null, null,
                "test content", invalidSourceType, null, false);

        // On FIXED code: should throw ValidationException for invalid sourceType
        // On UNFIXED code: no validation, IngestRouter silently falls back to
        // FeishuQaProcessor
        assertThrows(ValidationException.class,
                () -> service.createTask(request),
                "IngestService.createTask() should reject invalid sourceType '"
                        + invalidSourceType + "' with ValidationException, "
                        + "but no exception was thrown. IngestRouter silently falls back "
                        + "to FeishuQaProcessor for unknown types.");
    }

    // ── Defect 15: Null retryCount SQL Filter ──

    /**
     * Defect 15 - Null retryCount SQL Filter:
     * Create a task with retryCount=null, status=FAILED, and processed content.
     * Run the retry scheduler query. On unfixed code, retry_count < 3 evaluates
     * to NULL < 3 = false, excluding the task.
     * <p>
     * We verify this by checking that IngestService.createTask() initializes
     * retryCount to 0 (not null), preventing the SQL NULL comparison issue.
     * On unfixed code, retryCount is never initialized, remaining null.
     * Expected counterexample: retryCount is null after task creation.
     * </p>
     *
     * <b>Validates: Requirements 2.15</b>
     */
    @Example
    void nullRetryCountTaskShouldBeIncludedInRetryScan() {
        // The bug manifests in two ways:
        // 1. IngestService.createTask() never initializes retryCount (leaves it null)
        // 2. IngestRetryScheduler query uses .lt(retryCount, 3) which generates
        // "retry_count < 3" - this evaluates to false when retry_count IS NULL
        //
        // We test aspect 1: verify that retryCount is initialized to 0 on creation.
        // This is the primary fix path (initialize to prevent null in the first place).

        IngestTaskMapper mapper = mock(IngestTaskMapper.class);
        IngestAsyncWorker asyncWorker = mock(IngestAsyncWorker.class);
        DocumentService documentService = mock(DocumentService.class);

        // Setup mapper: no existing task (new request)
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mapper.insert(any(IngestTaskEntity.class))).thenAnswer(invocation -> {
            IngestTaskEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return 1;
        });

        IngestService service = new IngestService(mapper, asyncWorker, documentService);

        IngestRequest request = new IngestRequest(
                "req-null-retry-check", "user1", null, null,
                "test content", "MARKDOWN", null, false);

        service.createTask(request);

        // Capture the entity that was inserted
        ArgumentCaptor<IngestTaskEntity> entityCaptor = ArgumentCaptor.forClass(IngestTaskEntity.class);
        verify(mapper).insert(entityCaptor.capture());
        IngestTaskEntity createdTask = entityCaptor.getValue();

        // On FIXED code: retryCount should be initialized to 0 (not null)
        // On UNFIXED code: retryCount is null (never set during task creation)
        assertNotNull(createdTask.getRetryCount(),
                "IngestService.createTask() should initialize retryCount to a non-null value "
                        + "(e.g., 0), but retryCount is null. "
                        + "When this task fails and the retry scheduler runs, "
                        + "the SQL condition 'retry_count < 3' evaluates to "
                        + "'NULL < 3' which is false, excluding the task from retry.");

        assertEquals(0, createdTask.getRetryCount(),
                "retryCount should be initialized to 0, but was: " + createdTask.getRetryCount());
    }

    // ── Providers ──

    @Provide
    Arbitrary<Integer> oversizedBodySizes() {
        // Generate body sizes between 1 MB + 1 byte and 2 MB
        return Arbitraries.integers().between(1_048_577, 2_097_152);
    }

    @Provide
    Arbitrary<String> invalidSourceTypes() {
        return Arbitraries.of(
                "UNKNOWN_TYPE",
                "INVALID",
                "PDF",
                "WORD",
                "EXCEL",
                "HTML",
                "feishu_chat_invalid",
                "markdown_v2",
                "",
                "RANDOM_SOURCE");
    }

    // ── Helper Methods ──

    private KbProperties createKbProperties() {
        KbProperties props = new KbProperties();

        KbProperties.Ragflow ragflow = new KbProperties.Ragflow();
        ragflow.setBaseUrl("http://localhost:9380");
        ragflow.setApiKey("test-api-key");
        props.setRagflow(ragflow);

        KbProperties.Query query = new KbProperties.Query();
        query.setMaxSources(5);
        query.setMaxContentLength(2000);
        query.setMaxTotalLength(8000);
        props.setQuery(query);

        KbProperties.Ingest ingest = new KbProperties.Ingest();
        ingest.setAsyncPoolSize(4);
        ingest.setRetryMaxAttempts(3);
        props.setIngest(ingest);

        KbProperties.Minio minio = new KbProperties.Minio();
        minio.setRawBucket("kb-raw");
        minio.setProcessedBucket("kb-processed");
        props.setMinio(minio);

        KbProperties.Processor processor = new KbProperties.Processor();
        processor.setMinQaCount(3);
        props.setProcessor(processor);

        return props;
    }
}
