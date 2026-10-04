package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.client.RagflowClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.config.KbProperties.Ragflow;
import com.openclaw.kbbridge.dto.ragflow.DeleteDocumentResponse;
import com.openclaw.kbbridge.dto.ragflow.UpdateDocumentRequest;
import com.openclaw.kbbridge.dto.ragflow.UpdateDocumentResponse;
import com.openclaw.kbbridge.entity.KnowledgeDocumentEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.repository.KnowledgeDocumentMapper;
import com.openclaw.kbbridge.unified.UnifiedObjectStore;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DocumentService 单元测试。
 * <p>
 * 验证文档版本管理功能：createNewVersion、getLatestVersion、disableOldVersions。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;
    @Mock
    private RagflowClient ragflowClient;

    private KbProperties kbProperties;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        kbProperties = new KbProperties();
        Ragflow ragflow = new Ragflow();
        ragflow.setDatasetId("test-dataset-id");
        kbProperties.setRagflow(ragflow);
        documentService = new DocumentService(knowledgeDocumentMapper, ragflowClient, kbProperties);
    }

    // ── createNewVersion 测试 ──

    /**
     * 首次创建文档版本：无已有文档时，version 应为 1。
     */
    @Test
    void createNewVersion_noExistingDocs_createsVersion1() {
        Long taskId = 1L;
        String knowledgeType = "GUIDE";

        // getLatestVersion 返回 null（无已有文档）
        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // disableOldVersions 查询返回空列表
        when(knowledgeDocumentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            KnowledgeDocumentEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        }).when(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));

        KnowledgeDocumentEntity result = documentService.createNewVersion(
                taskId, knowledgeType, "测试文档", "ragflow-doc-001");

        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals(1, result.getVersion());
        assertEquals(taskId, result.getTaskId());
        assertEquals(knowledgeType, result.getKnowledgeType());
        assertEquals("测试文档", result.getTitle());
        assertEquals("ragflow-doc-001", result.getRagflowDocumentId());
        assertEquals(DocumentStatus.COMPLETED.name(), result.getStatus());
        assertEquals(ReviewStatus.CANDIDATE.name(), result.getReviewStatus());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());

        verify(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));
    }

    /**
     * 已有版本 1 时，创建新版本应为 version 2，并禁用旧版本。
     */
    @Test
    void createNewVersion_existingVersion1_createsVersion2AndDisablesOld() {
        Long taskId = 1L;
        String knowledgeType = "QA";

        // 已有版本 1 的文档
        KnowledgeDocumentEntity existingDoc = new KnowledgeDocumentEntity();
        existingDoc.setId(10L);
        existingDoc.setTaskId(taskId);
        existingDoc.setKnowledgeType(knowledgeType);
        existingDoc.setVersion(1);
        existingDoc.setStatus(DocumentStatus.COMPLETED.name());

        // getLatestVersion 返回已有文档
        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingDoc);
        // disableOldVersions 查询返回旧文档列表
        when(knowledgeDocumentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existingDoc));
        when(knowledgeDocumentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            KnowledgeDocumentEntity entity = invocation.getArgument(0);
            entity.setId(200L);
            return 1;
        }).when(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));

        KnowledgeDocumentEntity result = documentService.createNewVersion(
                taskId, knowledgeType, "更新文档", "ragflow-doc-002");

        // 新版本应为 2
        assertEquals(2, result.getVersion());
        assertEquals(200L, result.getId());
        assertEquals(DocumentStatus.COMPLETED.name(), result.getStatus());

        // 旧版本应被禁用
        assertEquals(DocumentStatus.DISABLED.name(), existingDoc.getStatus());

        // 验证旧版本被更新
        verify(knowledgeDocumentMapper).updateById(existingDoc);
        // 验证新版本被插入
        verify(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));
    }

    /**
     * 已有多个版本时，新版本号应基于最高版本递增。
     */
    @Test
    void createNewVersion_existingVersion3_createsVersion4() {
        Long taskId = 5L;
        String knowledgeType = "GUIDE";

        // 最新版本为 3
        KnowledgeDocumentEntity latestDoc = new KnowledgeDocumentEntity();
        latestDoc.setId(30L);
        latestDoc.setTaskId(taskId);
        latestDoc.setKnowledgeType(knowledgeType);
        latestDoc.setVersion(3);
        latestDoc.setStatus(DocumentStatus.COMPLETED.name());

        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latestDoc);
        // 只有最新版本未禁用
        when(knowledgeDocumentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(latestDoc));
        when(knowledgeDocumentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            KnowledgeDocumentEntity entity = invocation.getArgument(0);
            entity.setId(400L);
            return 1;
        }).when(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));

        KnowledgeDocumentEntity result = documentService.createNewVersion(
                taskId, knowledgeType, "第四版", "ragflow-doc-004");

        assertEquals(4, result.getVersion());
        assertEquals(DocumentStatus.DISABLED.name(), latestDoc.getStatus());
    }

    // ── getLatestVersion 测试 ──

    /**
     * 无文档时返回 null。
     */
    @Test
    void getLatestVersion_noDocuments_returnsNull() {
        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        KnowledgeDocumentEntity result = documentService.getLatestVersion(1L, "GUIDE");

        assertNull(result);
    }

    /**
     * 有文档时返回版本号最高的文档。
     */
    @Test
    void getLatestVersion_hasDocuments_returnsHighestVersion() {
        KnowledgeDocumentEntity latestDoc = new KnowledgeDocumentEntity();
        latestDoc.setId(50L);
        latestDoc.setTaskId(1L);
        latestDoc.setKnowledgeType("QA");
        latestDoc.setVersion(3);

        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latestDoc);

        KnowledgeDocumentEntity result = documentService.getLatestVersion(1L, "QA");

        assertNotNull(result);
        assertEquals(3, result.getVersion());
        assertEquals(50L, result.getId());
    }

    // ── 版本管理集成场景 ──

    /**
     * 连续创建两个版本：版本号应依次递增，旧版本被禁用。
     */
    @Test
    void createNewVersion_consecutiveVersions_incrementsCorrectly() {
        Long taskId = 10L;
        String knowledgeType = "GUIDE";

        // 第一次创建：无已有文档
        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(knowledgeDocumentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            KnowledgeDocumentEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return 1;
        }).when(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));

        KnowledgeDocumentEntity v1 = documentService.createNewVersion(
                taskId, knowledgeType, "版本1", "ragflow-v1");
        assertEquals(1, v1.getVersion());

        // 第二次创建：已有版本 1
        KnowledgeDocumentEntity v1ForLatest = new KnowledgeDocumentEntity();
        v1ForLatest.setId(1L);
        v1ForLatest.setTaskId(taskId);
        v1ForLatest.setKnowledgeType(knowledgeType);
        v1ForLatest.setVersion(1);
        v1ForLatest.setStatus(DocumentStatus.COMPLETED.name());

        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(v1ForLatest);
        when(knowledgeDocumentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(v1ForLatest));
        when(knowledgeDocumentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        doAnswer(invocation -> {
            KnowledgeDocumentEntity entity = invocation.getArgument(0);
            entity.setId(2L);
            return 1;
        }).when(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));

        KnowledgeDocumentEntity v2 = documentService.createNewVersion(
                taskId, knowledgeType, "版本2", "ragflow-v2");
        assertEquals(2, v2.getVersion());
        assertEquals(DocumentStatus.DISABLED.name(), v1ForLatest.getStatus());
    }

    /**
     * 不同 knowledgeType 的版本互不影响。
     */
    @Test
    void createNewVersion_differentKnowledgeTypes_independentVersioning() {
        Long taskId = 20L;

        // GUIDE 已有版本 2
        KnowledgeDocumentEntity guideLatest = new KnowledgeDocumentEntity();
        guideLatest.setId(100L);
        guideLatest.setTaskId(taskId);
        guideLatest.setKnowledgeType("GUIDE");
        guideLatest.setVersion(2);
        guideLatest.setStatus(DocumentStatus.COMPLETED.name());

        // 创建 QA 版本时，应从 1 开始（QA 无已有文档）
        when(knowledgeDocumentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(knowledgeDocumentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            KnowledgeDocumentEntity entity = invocation.getArgument(0);
            entity.setId(300L);
            return 1;
        }).when(knowledgeDocumentMapper).insert(any(KnowledgeDocumentEntity.class));

        KnowledgeDocumentEntity qaV1 = documentService.createNewVersion(
                taskId, "QA", "QA文档", "ragflow-qa-001");

        assertEquals(1, qaV1.getVersion());
        assertEquals("QA", qaV1.getKnowledgeType());
    }

    // ── disable 测试 ──

    /**
     * 禁用文档时，若有 ragflowDocumentId，应调用 RAGFlow deleteDocument API。
     */
    @Test
    void disable_withRagflowDocumentId_callsDeleteDocument() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(1L);
        doc.setRagflowDocumentId("ragflow-doc-001");
        doc.setDatasetName("ds-001");
        doc.setStatus(DocumentStatus.COMPLETED.name());

        when(knowledgeDocumentMapper.selectById(1L)).thenReturn(doc);
        when(knowledgeDocumentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        when(ragflowClient.deleteDocument(eq("ds-001"), eq("ragflow-doc-001"), anyString()))
                .thenReturn(new DeleteDocumentResponse(true));

        documentService.disable(1L);

        assertEquals(DocumentStatus.DISABLED.name(), doc.getStatus());
        verify(ragflowClient).deleteDocument(eq("ds-001"), eq("ragflow-doc-001"), anyString());
        verify(knowledgeDocumentMapper).updateById(doc);
    }

    /**
     * 禁用文档时，若无 ragflowDocumentId，不应调用 RAGFlow API。
     */
    @Test
    void disable_withoutRagflowDocumentId_doesNotCallDeleteDocument() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(2L);
        doc.setRagflowDocumentId(null);
        doc.setStatus(DocumentStatus.COMPLETED.name());

        when(knowledgeDocumentMapper.selectById(2L)).thenReturn(doc);
        when(knowledgeDocumentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        documentService.disable(2L);

        assertEquals(DocumentStatus.DISABLED.name(), doc.getStatus());
        verify(ragflowClient, never()).deleteDocument(anyString(), anyString(), anyString());
    }

    // ── enable 测试 ──

    /**
     * 启用文档时，若有 ragflowDocumentId，应调用 RAGFlow updateDocument API。
     */
    @Test
    void enable_withRagflowDocumentId_callsUpdateDocument() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(3L);
        doc.setRagflowDocumentId("ragflow-doc-003");
        doc.setDatasetName("ds-003");
        doc.setTitle("测试文档");
        doc.setStatus(DocumentStatus.DISABLED.name());

        when(knowledgeDocumentMapper.selectById(3L)).thenReturn(doc);
        when(knowledgeDocumentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
        when(ragflowClient.updateDocument(any(UpdateDocumentRequest.class), anyString()))
                .thenReturn(new UpdateDocumentResponse("ragflow-doc-003", true));

        documentService.enable(3L);

        assertEquals(DocumentStatus.COMPLETED.name(), doc.getStatus());
        verify(ragflowClient).updateDocument(any(UpdateDocumentRequest.class), anyString());
        verify(knowledgeDocumentMapper).updateById(doc);
    }

    /**
     * 启用文档时，若无 ragflowDocumentId，不应调用 RAGFlow API。
     */
    @Test
    void enable_withoutRagflowDocumentId_doesNotCallUpdateDocument() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(4L);
        doc.setRagflowDocumentId(null);
        doc.setStatus(DocumentStatus.DISABLED.name());

        when(knowledgeDocumentMapper.selectById(4L)).thenReturn(doc);
        when(knowledgeDocumentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);

        documentService.enable(4L);

        assertEquals(DocumentStatus.COMPLETED.name(), doc.getStatus());
        verify(ragflowClient, never()).updateDocument(any(UpdateDocumentRequest.class), anyString());
    }

    @Test
    void unifiedDetailReadsFinalObjectWithTwoMegabyteLimit() {
        var doc = new KnowledgeDocumentEntity();
        doc.setId(9L); doc.setDocumentId("logical-doc"); doc.setReleaseId("release");
        doc.setObjectKey("documents/logical-doc/releases/release/final.md");
        var objects = mock(UnifiedObjectStore.class);
        String markdown = "# Final content\n<script>never execute</script>";
        when(knowledgeDocumentMapper.selectById(9L)).thenReturn(doc);
        when(objects.get(doc.getObjectKey(), 2 * 1024 * 1024)).thenReturn(markdown.getBytes(StandardCharsets.UTF_8));
        documentService.setUnifiedObjectStore(objects);
        var detail = documentService.getDetail(9L);
        assertEquals(doc.getDocumentId(), detail.documentId());
        assertEquals(doc.getReleaseId(), detail.releaseId());
        assertEquals(doc.getObjectKey(), detail.objectKey());
        assertEquals(markdown, detail.contentPreview());
        verify(objects).get(doc.getObjectKey(), 2 * 1024 * 1024);
        verifyNoInteractions(ragflowClient);
    }

    @Test
    void unavailableUnifiedObjectDoesNotPreventDocumentMetadataDetail() {
        var doc = new KnowledgeDocumentEntity();
        doc.setId(10L); doc.setDocumentId("logical-doc"); doc.setObjectKey("documents/logical-doc/final.md");
        var objects = mock(UnifiedObjectStore.class);
        when(knowledgeDocumentMapper.selectById(10L)).thenReturn(doc);
        when(objects.get(doc.getObjectKey(), 2 * 1024 * 1024)).thenThrow(new IllegalArgumentException("Object exceeds limit"));
        documentService.setUnifiedObjectStore(objects);
        var detail = documentService.getDetail(10L);
        assertEquals(10L, detail.id());
        assertNull(detail.contentPreview());
    }

}
