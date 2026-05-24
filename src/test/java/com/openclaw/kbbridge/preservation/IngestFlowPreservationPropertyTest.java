package com.openclaw.kbbridge.preservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ingest.IngestRequest;
import com.openclaw.kbbridge.dto.ingest.IngestResponse;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.processor.KnowledgeProcessor;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.router.IngestRouter;
import com.openclaw.kbbridge.service.DocumentService;
import com.openclaw.kbbridge.service.IngestAsyncWorker;
import com.openclaw.kbbridge.service.IngestService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Preservation Property Tests for Ingest Flow (Phase 2).
 * <p>
 * These tests verify existing correct behavior on UNFIXED code that must be
 * preserved after bug fixes are applied. They are EXPECTED TO PASS on unfixed
 * code.
 * </p>
 * <p>
 * Tests follow observation-first methodology: observe behavior on unfixed code
 * for non-buggy inputs (unique requestId, valid sourceType, valid content).
 * </p>
 */
class IngestFlowPreservationPropertyTest {

        // ── Preservation P1: createTask() creates task with correct status ──

        /**
         * Preservation P1: For any single ingest request with unique requestId and
         * valid
         * content, createTask() creates a task with status=RECEIVED,
         * reviewStatus=CANDIDATE,
         * and returns an IngestResponse with the new taskId.
         * <p>
         * This verifies the core task creation flow is preserved after fixes.
         * </p>
         *
         * <b>Validates: Requirements 3.1</b>
         */
        @Property(tries = 30)
        void createTaskWithUniqueRequestIdSetsReceivedAndCandidate(
                        @ForAll("uniqueRequestIds") String requestId,
                        @ForAll("validSourceTypes") String sourceType,
                        @ForAll("validContents") String content) {

                // Setup mocks
                IngestTaskMapper mapper = mock(IngestTaskMapper.class);
                IngestAsyncWorker asyncWorker = mock(IngestAsyncWorker.class);
                DocumentService documentService = mock(DocumentService.class);

                // No existing task for this requestId (unique request)
                when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

                // Capture the inserted entity
                ArgumentCaptor<IngestTaskEntity> insertCaptor = ArgumentCaptor.forClass(IngestTaskEntity.class);
                when(mapper.insert(insertCaptor.capture())).thenAnswer(invocation -> {
                        IngestTaskEntity entity = invocation.getArgument(0);
                        entity.setId(42L);
                        return 1;
                });

                IngestService service = new IngestService(mapper, asyncWorker, documentService);

                IngestRequest request = new IngestRequest(
                                requestId, "user1", null, null,
                                content, sourceType, null, false);

                IngestResponse response = service.createTask(request);

                // Verify response
                assertNotNull(response, "Response should not be null");
                assertEquals(requestId, response.requestId(), "Response requestId should match");
                assertEquals(42L, response.taskId(), "Response should contain the new taskId");
                assertEquals(DocumentStatus.RECEIVED.name(), response.status(),
                                "Response status should be RECEIVED");
                assertFalse(response.duplicate(), "Response should not be marked as duplicate");

                // Verify the inserted entity
                IngestTaskEntity inserted = insertCaptor.getValue();
                assertEquals(requestId, inserted.getRequestId());
                assertEquals(DocumentStatus.RECEIVED.name(), inserted.getStatus(),
                                "Inserted task status should be RECEIVED");
                assertEquals(ReviewStatus.CANDIDATE.name(), inserted.getReviewStatus(),
                                "Inserted task reviewStatus should be CANDIDATE");
                assertEquals(sourceType, inserted.getSourceType());
                assertNotNull(inserted.getContentHash(), "Content hash should be computed");
                assertNotNull(inserted.getCreatedAt(), "CreatedAt should be set");
                assertNotNull(inserted.getUpdatedAt(), "UpdatedAt should be set");
        }

        // ── Preservation P2: Idempotency for existing requestId (single request) ──

        /**
         * Preservation P2: For any ingest request with a requestId that already exists
         * (single request, no concurrency), createTask() returns the existing task
         * without creating a duplicate.
         * <p>
         * This verifies the single-request idempotency check is preserved.
         * </p>
         *
         * <b>Validates: Requirements 3.2</b>
         */
        @Property(tries = 30)
        void createTaskWithExistingRequestIdReturnsExistingTask(
                        @ForAll("uniqueRequestIds") String requestId,
                        @ForAll("validSourceTypes") String sourceType,
                        @ForAll("validContents") String content) {

                IngestTaskMapper mapper = mock(IngestTaskMapper.class);
                IngestAsyncWorker asyncWorker = mock(IngestAsyncWorker.class);
                DocumentService documentService = mock(DocumentService.class);

                // Simulate existing task for this requestId
                IngestTaskEntity existingTask = new IngestTaskEntity();
                existingTask.setId(99L);
                existingTask.setRequestId(requestId);
                existingTask.setStatus(DocumentStatus.COMPLETED.name());
                existingTask.setSourceType(sourceType);

                when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingTask);

                IngestService service = new IngestService(mapper, asyncWorker, documentService);

                IngestRequest request = new IngestRequest(
                                requestId, "user1", null, null,
                                content, sourceType, null, false);

                IngestResponse response = service.createTask(request);

                // Verify idempotent response returns existing task
                assertNotNull(response, "Response should not be null");
                assertEquals(requestId, response.requestId(), "Response requestId should match");
                assertEquals(99L, response.taskId(),
                                "Response should return the existing taskId, not create a new one");
                assertEquals(DocumentStatus.COMPLETED.name(), response.status(),
                                "Response should return the existing task's status");
                assertFalse(response.duplicate(), "Idempotent response should not be marked as duplicate");

                // Verify no insert was attempted
                verify(mapper, never()).insert(any(IngestTaskEntity.class));
        }

        // ── Preservation P3: IngestRouter routes to correct processors ──

        /**
         * Preservation P3: For any valid sourceType in {MARKDOWN, TUTORIAL, NOTE},
         * IngestRouter.route() returns MarkdownKnowledgeProcessor; for FEISHU_CHAT
         * returns FeishuQaProcessor.
         * <p>
         * This verifies the routing logic is preserved after fixes.
         * </p>
         *
         * <b>Validates: Requirements 3.14</b>
         */
        @Property(tries = 20)
        void ingestRouterRoutesToCorrectProcessor(
                        @ForAll("markdownSourceTypes") String markdownType) {

                KnowledgeProcessor markdownProcessor = mock(KnowledgeProcessor.class);
                KnowledgeProcessor feishuQaProcessor = mock(KnowledgeProcessor.class);
                KnowledgeProcessor attachmentProcessor = mock(KnowledgeProcessor.class);

                Map<String, KnowledgeProcessor> processorMap = new HashMap<>();
                processorMap.put("markdownKnowledgeProcessor", markdownProcessor);
                processorMap.put("feishuQaProcessor", feishuQaProcessor);
                processorMap.put("attachmentProcessor", attachmentProcessor);

                IngestRouter router = new IngestRouter(processorMap);

                // MARKDOWN, TUTORIAL, NOTE → markdownKnowledgeProcessor
                KnowledgeProcessor result = router.route(markdownType);
                assertSame(markdownProcessor, result,
                                "sourceType '" + markdownType + "' should route to markdownKnowledgeProcessor");
        }

        /**
         * Preservation P3 (continued): FEISHU_CHAT routes to FeishuQaProcessor.
         *
         * <b>Validates: Requirements 3.14</b>
         */
        @Example
        void feishuChatRoutesToFeishuQaProcessor() {
                KnowledgeProcessor markdownProcessor = mock(KnowledgeProcessor.class);
                KnowledgeProcessor feishuQaProcessor = mock(KnowledgeProcessor.class);
                KnowledgeProcessor attachmentProcessor = mock(KnowledgeProcessor.class);

                Map<String, KnowledgeProcessor> processorMap = new HashMap<>();
                processorMap.put("markdownKnowledgeProcessor", markdownProcessor);
                processorMap.put("feishuQaProcessor", feishuQaProcessor);
                processorMap.put("attachmentProcessor", attachmentProcessor);

                IngestRouter router = new IngestRouter(processorMap);

                KnowledgeProcessor result = router.route("FEISHU_CHAT");
                assertSame(feishuQaProcessor, result,
                                "FEISHU_CHAT should route to feishuQaProcessor");
        }

        // ── Preservation P4: MinIO put operations use correct bucket and path ──

        /**
         * Preservation P4: For any call to MinioStorageClient.putRawObject(),
         * putProcessedGuide(), putProcessedQa(), putFailedObject(), the correct
         * bucket and date-based path format is used.
         * <p>
         * This verifies MinIO storage path conventions are preserved.
         * We call the public put methods with a mocked MinioClient and verify
         * the returned object keys follow the expected date-based path format.
         * </p>
         *
         * <b>Validates: Requirements 3.4</b>
         */
        @Property(tries = 30)
        void minioStorageUsesCorrectDatePathFormat(
                        @ForAll("validSourceTypes") String sourceType,
                        @ForAll("uniqueRequestIds") String requestId,
                        @ForAll("validContents") String content) throws Exception {

                io.minio.MinioClient minioClient = mock(io.minio.MinioClient.class);
                KbProperties kbProperties = createKbProperties();
                MinioStorageClient storageClient = new MinioStorageClient(minioClient, kbProperties);

                String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

                // Test putRawObject path format
                String rawKey = storageClient.putRawObject(sourceType, content);
                assertTrue(rawKey.startsWith(sourceType + "/"),
                                "Raw path should start with sourceType prefix: " + rawKey);
                assertTrue(rawKey.contains(today),
                                "Raw path should contain today's date: " + rawKey);
                assertTrue(rawKey.endsWith("-raw.md"),
                                "Raw path should end with -raw.md: " + rawKey);

                // Test putProcessedGuide path format
                String guideKey = storageClient.putProcessedGuide(content);
                assertTrue(guideKey.startsWith("guide/"),
                                "Guide path should start with 'guide/' prefix: " + guideKey);
                assertTrue(guideKey.contains(today),
                                "Guide path should contain today's date: " + guideKey);
                assertTrue(guideKey.endsWith("-guide.md"),
                                "Guide path should end with -guide.md: " + guideKey);

                // Test putProcessedQa path format
                String qaKey = storageClient.putProcessedQa(content);
                assertTrue(qaKey.startsWith("qa/"),
                                "QA path should start with 'qa/' prefix: " + qaKey);
                assertTrue(qaKey.contains(today),
                                "QA path should contain today's date: " + qaKey);
                assertTrue(qaKey.endsWith("-qa.md"),
                                "QA path should end with -qa.md: " + qaKey);

                // Test putFailedObject path format
                String failedKey = storageClient.putFailedObject(content);
                assertTrue(failedKey.startsWith("failed/"),
                                "Failed path should start with 'failed/' prefix: " + failedKey);
                assertTrue(failedKey.contains(today),
                                "Failed path should contain today's date: " + failedKey);
                assertTrue(failedKey.endsWith("-failed.md"),
                                "Failed path should end with -failed.md: " + failedKey);
        }

        /**
         * Preservation P4 (continued): Verify that put methods use the correct buckets
         * from KbProperties configuration.
         *
         * <b>Validates: Requirements 3.4</b>
         */
        @Property(tries = 20)
        void minioPutMethodsUseCorrectBuckets(
                        @ForAll("uniqueRequestIds") String requestId,
                        @ForAll("validContents") String content) throws Exception {

                io.minio.MinioClient minioClient = mock(io.minio.MinioClient.class);
                KbProperties kbProperties = createKbProperties();

                MinioStorageClient storageClient = new MinioStorageClient(minioClient, kbProperties);

                // Capture PutObjectArgs to verify bucket
                ArgumentCaptor<io.minio.PutObjectArgs> putCaptor = ArgumentCaptor
                                .forClass(io.minio.PutObjectArgs.class);

                // putRawObject should use rawBucket
                storageClient.putRawObject("MARKDOWN", content);
                verify(minioClient).putObject(putCaptor.capture());
                assertEquals("kb-raw", putCaptor.getValue().bucket(),
                                "putRawObject should use the raw bucket 'kb-raw'");

                reset(minioClient);

                // putProcessedGuide should use processedBucket
                storageClient.putProcessedGuide(content);
                verify(minioClient).putObject(putCaptor.capture());
                assertEquals("kb-processed", putCaptor.getValue().bucket(),
                                "putProcessedGuide should use the processed bucket 'kb-processed'");

                reset(minioClient);

                // putProcessedQa should use processedBucket
                storageClient.putProcessedQa(content);
                verify(minioClient).putObject(putCaptor.capture());
                assertEquals("kb-processed", putCaptor.getValue().bucket(),
                                "putProcessedQa should use the processed bucket 'kb-processed'");

                reset(minioClient);

                // putFailedObject should use processedBucket
                storageClient.putFailedObject(content);
                verify(minioClient).putObject(putCaptor.capture());
                assertEquals("kb-processed", putCaptor.getValue().bucket(),
                                "putFailedObject should use the processed bucket 'kb-processed'");
        }

        // ── Providers ──

        @Provide
        Arbitrary<String> uniqueRequestIds() {
                return Arbitraries.strings()
                                .alpha()
                                .ofMinLength(5)
                                .ofMaxLength(20)
                                .map(s -> "req-" + s);
        }

        @Provide
        Arbitrary<String> validSourceTypes() {
                return Arbitraries.of("MARKDOWN", "TUTORIAL", "NOTE", "FEISHU_CHAT");
        }

        @Provide
        Arbitrary<String> markdownSourceTypes() {
                return Arbitraries.of("MARKDOWN", "TUTORIAL", "NOTE");
        }

        @Provide
        Arbitrary<String> validContents() {
                return Arbitraries.strings()
                                .alpha()
                                .ofMinLength(10)
                                .ofMaxLength(200)
                                .map(s -> "Content: " + s);
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

                KbProperties.Minio minio = new KbProperties.Minio();
                minio.setRawBucket("kb-raw");
                minio.setProcessedBucket("kb-processed");
                props.setMinio(minio);

                KbProperties.Ingest ingest = new KbProperties.Ingest();
                ingest.setAsyncPoolSize(4);
                props.setIngest(ingest);

                KbProperties.Processor processor = new KbProperties.Processor();
                processor.setMinQaCount(3);
                props.setProcessor(processor);

                return props;
        }
}
