package com.openclaw.kbbridge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.entity.KnowledgeDocumentEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.repository.KnowledgeDocumentMapper;
import com.openclaw.kbbridge.service.CandidateEvalService;
import com.openclaw.kbbridge.service.DocumentService;
import com.openclaw.kbbridge.service.IngestService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 分页列表端点属性测试。
 * <p>
 * 使用 jqwik 属性测试框架验证 IngestController 和 DocumentController 的分页列表端点
 * 在各种 page、size 和 status 过滤参数下返回正确的分页结果。
 * </p>
 * <p>
 * Feature: web-management-console, Property 5: Paginated List Correctness
 * Validates: Requirements 9.1, 9.2, 9.3
 */
@Tag("Feature: web-management-console, Property 5: Paginated List Correctness")
class PaginatedListPropertyTest {

        // ========== IngestController Tests ==========

        /**
         * IngestController.listTasks 分页属性测试：
         * (a) records.length ≤ size
         * (b) total 反映匹配过滤条件的记录总数
         * (c) 每条记录匹配过滤条件
         * (d) current 等于请求的 page
         * <p>
         * **Validates: Requirements 9.1, 9.3**
         */
        @Property(tries = 100)
        void ingestTaskListPaginationIsCorrect(
                        @ForAll("validPage") int page,
                        @ForAll("validSize") int size,
                        @ForAll("optionalIngestStatus") String statusFilter,
                        @ForAll("ingestRecordCount") int totalMatchingRecords) {

                // Arrange: create mock dependencies
                IngestService ingestService = mock(IngestService.class);
                CandidateEvalService candidateEvalService = mock(CandidateEvalService.class);
                IngestTaskMapper ingestTaskMapper = mock(IngestTaskMapper.class);

                // Calculate how many records should be on this page
                int recordsOnPage = Math.min(size, Math.max(0, totalMatchingRecords - (page - 1) * size));

                // Generate entities that all match the filter
                String effectiveStatus = (statusFilter == null || statusFilter.isBlank()) ? null : statusFilter;
                List<IngestTaskEntity> records = IntStream.range(0, recordsOnPage)
                                .mapToObj(i -> {
                                        IngestTaskEntity entity = new IngestTaskEntity();
                                        entity.setId((long) (i + 1 + (page - 1) * size));
                                        entity.setRequestId("req-" + entity.getId());
                                        entity.setUserId("user-test");
                                        entity.setSourceType("MARKDOWN");
                                        // If a status filter is active, all records must match it
                                        entity.setStatus(effectiveStatus != null ? effectiveStatus
                                                        : DocumentStatus.RECEIVED.name());
                                        entity.setReviewStatus("CANDIDATE");
                                        entity.setCreatedAt(LocalDateTime.now());
                                        entity.setUpdatedAt(LocalDateTime.now());
                                        return entity;
                                })
                                .toList();

                // Build the Page result that the mapper would return
                Page<IngestTaskEntity> mockPage = new Page<>(page, size);
                mockPage.setRecords(records);
                mockPage.setTotal(totalMatchingRecords);

                when(ingestTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                IngestController controller = new IngestController(ingestService, candidateEvalService,
                                ingestTaskMapper);

                // Act
                ResponseEntity<Page<IngestTaskEntity>> response = controller.listTasks(page, size, statusFilter, null);

                // Assert
                assertNotNull(response.getBody(), "Response body should not be null");
                Page<IngestTaskEntity> result = response.getBody();

                // (a) records.length ≤ size
                assertTrue(result.getRecords().size() <= size,
                                "records.length (" + result.getRecords().size() + ") should be ≤ size (" + size + ")");

                // (b) total reflects count matching filter
                assertEquals(totalMatchingRecords, result.getTotal(),
                                "total should reflect the count of all records matching the filter");

                // (c) every record matches filter criteria
                if (effectiveStatus != null) {
                        for (IngestTaskEntity record : result.getRecords()) {
                                assertEquals(effectiveStatus, record.getStatus(),
                                                "Every record should match the status filter: " + effectiveStatus);
                        }
                }

                // (d) current equals requested page
                assertEquals(page, result.getCurrent(),
                                "current (" + result.getCurrent() + ") should equal requested page (" + page + ")");

                // Verify the mapper was called with correct page parameters
                ArgumentCaptor<Page<IngestTaskEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
                verify(ingestTaskMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
                assertEquals(page, pageCaptor.getValue().getCurrent(),
                                "Mapper should be called with the requested page number");
                assertEquals(size, pageCaptor.getValue().getSize(),
                                "Mapper should be called with the requested page size");
        }

        // ========== DocumentController Tests ==========

        /**
         * DocumentController.listDocuments 分页属性测试：
         * (a) records.length ≤ size
         * (b) total 反映匹配过滤条件的记录总数
         * (c) 每条记录匹配过滤条件
         * (d) current 等于请求的 page
         * <p>
         * **Validates: Requirements 9.2, 9.3**
         */
        @Property(tries = 100)
        void documentListPaginationIsCorrect(
                        @ForAll("validPage") int page,
                        @ForAll("validSize") int size,
                        @ForAll("optionalDocumentStatus") String statusFilter,
                        @ForAll("documentRecordCount") int totalMatchingRecords) {

                // Arrange: create mock dependencies
                DocumentService documentService = mock(DocumentService.class);
                KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);

                // Calculate how many records should be on this page
                int recordsOnPage = Math.min(size, Math.max(0, totalMatchingRecords - (page - 1) * size));

                // Generate entities that all match the filter
                String effectiveStatus = (statusFilter == null || statusFilter.isBlank()) ? null : statusFilter;
                List<KnowledgeDocumentEntity> records = IntStream.range(0, recordsOnPage)
                                .mapToObj(i -> {
                                        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
                                        entity.setId((long) (i + 1 + (page - 1) * size));
                                        entity.setTaskId((long) (i + 100));
                                        entity.setKnowledgeType("GUIDE");
                                        entity.setTitle("Document " + entity.getId());
                                        entity.setTopic("Topic " + entity.getId());
                                        // If a status filter is active, all records must match it
                                        entity.setStatus(effectiveStatus != null ? effectiveStatus
                                                        : DocumentStatus.COMPLETED.name());
                                        entity.setReviewStatus("APPROVED");
                                        entity.setVersion(1);
                                        entity.setCreatedAt(LocalDateTime.now());
                                        entity.setUpdatedAt(LocalDateTime.now());
                                        return entity;
                                })
                                .toList();

                // Build the Page result that the mapper would return
                Page<KnowledgeDocumentEntity> mockPage = new Page<>(page, size);
                mockPage.setRecords(records);
                mockPage.setTotal(totalMatchingRecords);

                when(knowledgeDocumentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                DocumentController controller = new DocumentController(documentService, knowledgeDocumentMapper);

                // Act
                ResponseEntity<Page<KnowledgeDocumentEntity>> response = controller.listDocuments(page, size,
                                statusFilter, null);

                // Assert
                assertNotNull(response.getBody(), "Response body should not be null");
                Page<KnowledgeDocumentEntity> result = response.getBody();

                // (a) records.length ≤ size
                assertTrue(result.getRecords().size() <= size,
                                "records.length (" + result.getRecords().size() + ") should be ≤ size (" + size + ")");

                // (b) total reflects count matching filter
                assertEquals(totalMatchingRecords, result.getTotal(),
                                "total should reflect the count of all records matching the filter");

                // (c) every record matches filter criteria
                if (effectiveStatus != null) {
                        for (KnowledgeDocumentEntity record : result.getRecords()) {
                                assertEquals(effectiveStatus, record.getStatus(),
                                                "Every record should match the status filter: " + effectiveStatus);
                        }
                }

                // (d) current equals requested page
                assertEquals(page, result.getCurrent(),
                                "current (" + result.getCurrent() + ") should equal requested page (" + page + ")");

                // Verify the mapper was called with correct page parameters
                ArgumentCaptor<Page<KnowledgeDocumentEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
                verify(knowledgeDocumentMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
                assertEquals(page, pageCaptor.getValue().getCurrent(),
                                "Mapper should be called with the requested page number");
                assertEquals(size, pageCaptor.getValue().getSize(),
                                "Mapper should be called with the requested page size");
        }

        // ========== Custom Arbitrary Providers ==========

        /**
         * Generates valid page numbers (≥ 1).
         */
        @Provide
        Arbitrary<Integer> validPage() {
                return Arbitraries.integers().between(1, 100);
        }

        /**
         * Generates valid page sizes (≥ 1).
         */
        @Provide
        Arbitrary<Integer> validSize() {
                return Arbitraries.integers().between(1, 100);
        }

        /**
         * Generates optional ingest task status filter values.
         * Returns null (no filter) or one of the valid DocumentStatus values used for
         * ingest tasks.
         */
        @Provide
        Arbitrary<String> optionalIngestStatus() {
                Arbitrary<String> statusValues = Arbitraries.of(
                                DocumentStatus.RECEIVED.name(),
                                DocumentStatus.RAW_STORED.name(),
                                DocumentStatus.PROCESSING.name(),
                                DocumentStatus.COMPLETED.name(),
                                DocumentStatus.FAILED.name());
                return Arbitraries.oneOf(
                                Arbitraries.just(null),
                                statusValues);
        }

        /**
         * Generates optional document status filter values.
         * Returns null (no filter) or one of the valid document status values.
         */
        @Provide
        Arbitrary<String> optionalDocumentStatus() {
                Arbitrary<String> statusValues = Arbitraries.of(
                                DocumentStatus.COMPLETED.name(),
                                DocumentStatus.DISABLED.name(),
                                DocumentStatus.FAILED.name());
                return Arbitraries.oneOf(
                                Arbitraries.just(null),
                                statusValues);
        }

        /**
         * Generates total matching record counts for ingest tasks.
         * Includes 0 (empty result) and various sizes.
         */
        @Provide
        Arbitrary<Integer> ingestRecordCount() {
                return Arbitraries.integers().between(0, 500);
        }

        /**
         * Generates total matching record counts for documents.
         * Includes 0 (empty result) and various sizes.
         */
        @Provide
        Arbitrary<Integer> documentRecordCount() {
                return Arbitraries.integers().between(0, 500);
        }
}
