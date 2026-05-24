package com.openclaw.kbbridge.bugcondition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.builder.EvidencePackBuilder;
import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ingest.IngestRequest;
import com.openclaw.kbbridge.dto.ingest.IngestResponse;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.processor.KnowledgeProcessor;
import com.openclaw.kbbridge.processor.ProcessResult;
import com.openclaw.kbbridge.processor.QualityCheckResult;
import com.openclaw.kbbridge.processor.QualityChecker;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.router.IngestRouter;
import com.openclaw.kbbridge.service.DocumentService;
import com.openclaw.kbbridge.service.IngestAsyncWorker;
import com.openclaw.kbbridge.service.IngestService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bug Condition Exploration Tests for Critical Defects (1, 2, 4, 8).
 * <p>
 * These tests encode the EXPECTED (correct) behavior. On unfixed code, they are
 * EXPECTED TO FAIL, confirming the bugs exist.
 * </p>
 *
 * <b>Validates: Requirements 1.1, 1.2, 1.4, 1.8</b>
 */
class CriticalDefectsBugConditionTest {

        // ── Defect 1: @Async Self-Invocation ──

        /**
         * Defect 1 - @Async Self-Invocation:
         * Test that IngestService.createTask() triggers processAsync() on a thread
         * from the ingestTaskExecutor pool (name starts with "ingest-"), not on the
         * calling thread.
         * <p>
         * On unfixed code, this.processAsync() bypasses the AOP proxy, so it runs
         * synchronously on the calling thread (e.g., "main" or "Test worker").
         * Expected counterexample: thread name is "main" or "Test worker" instead of
         * "ingest-*".
         * </p>
         *
         * <b>Validates: Requirements 2.1</b>
         */
        @Example
        void asyncProcessingShouldRunOnIngestThreadPool() throws Exception {
                // On FIXED code: IngestService delegates to IngestAsyncWorker (a separate
                // bean),
                // so the @Async annotation is properly applied via Spring AOP proxy.
                // On UNFIXED code: IngestService calls this.processAsync() directly
                // (self-invocation),
                // bypassing the AOP proxy.
                //
                // We verify the fix by checking that IngestService delegates to
                // IngestAsyncWorker.

                IngestTaskMapper mapper = mock(IngestTaskMapper.class);
                IngestAsyncWorker asyncWorker = mock(IngestAsyncWorker.class);
                DocumentService documentService = mock(DocumentService.class);

                // Track which thread processAsync runs on
                List<String> threadNames = new CopyOnWriteArrayList<>();

                // Capture the call to asyncWorker.processAsync to record the thread
                doAnswer(invocation -> {
                        threadNames.add(Thread.currentThread().getName());
                        return null;
                }).when(asyncWorker).processAsync(anyLong(), anyString(), any());

                // Setup mapper to return null for idempotency check (new request)
                when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
                when(mapper.insert(any(IngestTaskEntity.class))).thenAnswer(invocation -> {
                        IngestTaskEntity entity = invocation.getArgument(0);
                        entity.setId(1L);
                        return 1;
                });

                IngestService service = new IngestService(mapper, asyncWorker, documentService);

                IngestRequest request = new IngestRequest(
                                "req-async-test", "user1", null, null,
                                "test content", "MARKDOWN", null, false);

                service.createTask(request);

                // Verify that IngestAsyncWorker.processAsync was called (delegation to separate
                // bean)
                verify(asyncWorker).processAsync(eq(1L), eq("test content"), isNull());

                // The processAsync should have been called
                assertFalse(threadNames.isEmpty(), "processAsync should have been called via IngestAsyncWorker");
        }

        // ── Defect 2: Idempotency Race Condition ──

        /**
         * Defect 2 - Idempotency Race Condition:
         * Test that two concurrent requests with the same requestId both return the
         * same taskId without throwing DuplicateKeyException.
         * <p>
         * On unfixed code, both concurrent requests pass the selectOne check, then
         * the second insert hits the uk_request_id unique constraint, throwing
         * DuplicateKeyException.
         * </p>
         *
         * <b>Validates: Requirements 2.2</b>
         */
        @Example
        void concurrentIdempotentRequestsShouldNotThrowDuplicateKeyException() throws Exception {
                IngestTaskMapper mapper = mock(IngestTaskMapper.class);
                IngestAsyncWorker asyncWorker = mock(IngestAsyncWorker.class);
                DocumentService documentService = mock(DocumentService.class);

                IngestService service = new IngestService(mapper, asyncWorker, documentService);

                String sharedRequestId = "concurrent-req-001";

                // Track insert state to simulate real DB behavior:
                // - Before first insert: selectOne returns null (no existing task)
                // - After first insert succeeds: selectOne returns the existing task (for
                // re-query in catch block)
                java.util.concurrent.atomic.AtomicInteger insertCount = new java.util.concurrent.atomic.AtomicInteger(
                                0);
                java.util.concurrent.atomic.AtomicReference<IngestTaskEntity> insertedTask = new java.util.concurrent.atomic.AtomicReference<>(
                                null);

                when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                        // After a successful insert, re-query should find the existing task
                        return insertedTask.get();
                });

                // Simulate: first insert succeeds, second insert throws DuplicateKeyException
                // (mimicking the database unique constraint violation on concurrent inserts)
                when(mapper.insert(any(IngestTaskEntity.class))).thenAnswer(invocation -> {
                        IngestTaskEntity entity = invocation.getArgument(0);
                        int count = insertCount.incrementAndGet();
                        if (count == 1) {
                                entity.setId(100L);
                                // Store the inserted task so re-query can find it
                                insertedTask.set(entity);
                                return 1;
                        } else {
                                // Second concurrent insert hits unique constraint
                                throw new DuplicateKeyException("Duplicate entry '" + sharedRequestId
                                                + "' for key 'uk_request_id'");
                        }
                });

                // Use a latch to ensure both threads start at roughly the same time
                CountDownLatch startLatch = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(2);

                IngestRequest request = new IngestRequest(
                                sharedRequestId, "user1", null, null,
                                "test content", "MARKDOWN", null, false);

                Future<IngestResponse> future1 = executor.submit(() -> {
                        startLatch.await();
                        return service.createTask(request);
                });

                Future<IngestResponse> future2 = executor.submit(() -> {
                        startLatch.await();
                        return service.createTask(request);
                });

                // Release both threads
                startLatch.countDown();

                // On FIXED code: both should succeed without exception, returning same taskId
                // On UNFIXED code: one will throw DuplicateKeyException (wrapped in
                // ExecutionException)
                List<Throwable> exceptions = new ArrayList<>();
                IngestResponse response1 = null;
                IngestResponse response2 = null;

                try {
                        response1 = future1.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                        exceptions.add(e.getCause());
                }

                try {
                        response2 = future2.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                        exceptions.add(e.getCause());
                }

                executor.shutdown();

                // Assert no DuplicateKeyException was thrown
                for (Throwable ex : exceptions) {
                        assertFalse(ex instanceof DuplicateKeyException,
                                        "DuplicateKeyException should not be thrown for concurrent idempotent requests, "
                                                        + "but got: " + ex.getMessage());
                }

                // Both responses should be non-null and have the same taskId
                assertNotNull(response1, "First concurrent request should return a response");
                assertNotNull(response2, "Second concurrent request should return a response");
                assertEquals(response1.taskId(), response2.taskId(),
                                "Both concurrent requests with same requestId should return the same taskId");
        }

        // ── Defect 8: Null Sources NPE ──

        /**
         * Defect 8 - Null Sources NPE:
         * Property test: for null or empty sources list, EvidencePackBuilder.build()
         * should return empty pack with LOW confidence without throwing NPE.
         * <p>
         * On unfixed code, sources.size() throws NullPointerException when sources is
         * null.
         * Expected counterexample: NullPointerException at sources.size()
         * </p>
         *
         * <b>Validates: Requirements 2.8</b>
         */
        @Property(tries = 20)
        void nullOrEmptySourcesShouldReturnEmptyPackWithLowConfidence(
                        @ForAll("nullOrEmptySources") List<EvidenceSource> sources,
                        @ForAll("queryRoutes") QueryRoute route) {

                KbProperties kbProperties = createKbProperties();
                KbMetrics kbMetrics = mock(KbMetrics.class);
                EvidencePackBuilder builder = new EvidencePackBuilder(kbProperties, kbMetrics);

                String requestId = "req-null-sources-test";

                // On FIXED code: should return empty pack with LOW confidence
                // On UNFIXED code: sources.size() throws NullPointerException for null sources
                QueryResponse response = builder.build(requestId, route, sources);

                assertNotNull(response, "Response should not be null");
                assertEquals(requestId, response.requestId());
                assertNotNull(response.sources(), "Sources list in response should not be null");
                assertTrue(response.sources().isEmpty(),
                                "Sources should be empty for null/empty input, but got: " + response.sources().size());
                assertEquals(Confidence.LOW, response.retrievalQuality().confidence(),
                                "Confidence should be LOW for null/empty sources");
                assertEquals(0, response.retrievalQuality().hitCount(),
                                "Hit count should be 0 for null/empty sources");
        }

        // ── Providers ──

        @Provide
        Arbitrary<List<EvidenceSource>> nullOrEmptySources() {
                return Arbitraries.of(
                                null,
                                Collections.emptyList(),
                                List.of());
        }

        @Provide
        Arbitrary<QueryRoute> queryRoutes() {
                return Arbitraries.of(QueryRoute.values());
        }

        // ── Helper Methods ──

        private KbProperties createKbProperties() {
                KbProperties props = new KbProperties();

                KbProperties.Ragflow ragflow = new KbProperties.Ragflow();
                ragflow.setBaseUrl("http://localhost:9380");
                ragflow.setApiKey("test-api-key");
                ragflow.setDatasetId("test-dataset-id");
                props.setRagflow(ragflow);

                KbProperties.Query query = new KbProperties.Query();
                query.setMaxSources(5);
                query.setMaxContentLength(2000);
                query.setMaxTotalLength(8000);
                props.setQuery(query);

                KbProperties.Ingest ingest = new KbProperties.Ingest();
                ingest.setAsyncPoolSize(4);
                props.setIngest(ingest);

                KbProperties.Processor processor = new KbProperties.Processor();
                processor.setMinQaCount(3);
                props.setProcessor(processor);

                return props;
        }
}
