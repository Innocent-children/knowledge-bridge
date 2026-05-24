package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.client.RagflowClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.document.DocumentDetailResponse;
import com.openclaw.kbbridge.dto.ragflow.UpdateDocumentRequest;
import com.openclaw.kbbridge.entity.KnowledgeDocumentEntity;
import com.openclaw.kbbridge.exception.BizException;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.repository.KnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档管理服务。
 * <p>
 * 提供文档禁用、启用和详情查询功能：
 * <ul>
 * <li>禁用：将文档状态置为 DISABLED，调用 RAGFlow API 排除文档</li>
 * <li>启用：恢复文档状态为 COMPLETED，调用 RAGFlow API 重新加入检索</li>
 * <li>详情：返回文档完整信息</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class DocumentService {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final RagflowClient ragflowClient;
    private final KbProperties kbProperties;

    public DocumentService(KnowledgeDocumentMapper knowledgeDocumentMapper,
            RagflowClient ragflowClient,
            KbProperties kbProperties) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.ragflowClient = ragflowClient;
        this.kbProperties = kbProperties;
    }

    /**
     * 禁用文档。
     * <p>
     * 将文档状态置为 DISABLED，并调用 RAGFlow API 将该文档从检索中排除。
     * 不物理删除 MinIO 中的文件。
     * </p>
     *
     * @param documentId 文档 ID
     * @throws BizException 文档不存在时抛出
     */
    public void disable(Long documentId) {
        KnowledgeDocumentEntity doc = findDocumentOrThrow(documentId);

        doc.setStatus(DocumentStatus.DISABLED.name());
        doc.setUpdatedAt(LocalDateTime.now());

        // 调用 RAGFlow API 排除文档
        if (doc.getRagflowDocumentId() != null) {
            String requestId = UUID.randomUUID().toString();
            String datasetId = doc.getDatasetName() != null
                    ? doc.getDatasetName()
                    : kbProperties.getRagflow().getDatasetId();
            ragflowClient.deleteDocument(datasetId, doc.getRagflowDocumentId(), requestId);
            log.info("文档禁用: documentId={}, ragflowDocumentId={}, 已调用 RAGFlow deleteDocument API",
                    documentId, doc.getRagflowDocumentId());
        }

        knowledgeDocumentMapper.updateById(doc);
        log.info("文档已禁用: documentId={}", documentId);
    }

    /**
     * 启用文档。
     * <p>
     * 恢复文档状态为 COMPLETED，并调用 RAGFlow API 重新将文档加入检索。
     * </p>
     *
     * @param documentId 文档 ID
     * @throws BizException 文档不存在时抛出
     */
    public void enable(Long documentId) {
        KnowledgeDocumentEntity doc = findDocumentOrThrow(documentId);

        doc.setStatus(DocumentStatus.COMPLETED.name());
        doc.setUpdatedAt(LocalDateTime.now());

        // 调用 RAGFlow API 重新加入检索
        if (doc.getRagflowDocumentId() != null) {
            String requestId = UUID.randomUUID().toString();
            UpdateDocumentRequest updateRequest = new UpdateDocumentRequest(
                    doc.getDatasetName(), doc.getRagflowDocumentId(),
                    doc.getTitle(), DocumentStatus.COMPLETED.name());
            ragflowClient.updateDocument(updateRequest, requestId);
            log.info("文档启用: documentId={}, ragflowDocumentId={}, 已调用 RAGFlow updateDocument API",
                    documentId, doc.getRagflowDocumentId());
        }

        knowledgeDocumentMapper.updateById(doc);
        log.info("文档已启用: documentId={}", documentId);
    }

    /**
     * 查询文档详情。
     *
     * @param documentId 文档 ID
     * @return 文档详情，文档不存在时返回 null
     */
    public DocumentDetailResponse getDetail(Long documentId) {
        KnowledgeDocumentEntity doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            return null;
        }
        return toDetailResponse(doc);
    }

    // ── 版本管理方法 ──

    /**
     * 创建新版本文档。
     * <p>
     * 查找同一 taskId 和 knowledgeType 下未禁用的文档，将它们全部禁用，
     * 然后创建一个新版本（version = 最大已有版本 + 1），状态为 COMPLETED。
     * </p>
     *
     * @param taskId            关联入库任务 ID
     * @param knowledgeType     知识类型（GUIDE/QA）
     * @param title             文档标题
     * @param ragflowDocumentId RAGFlow 文档 ID
     * @return 新创建的文档实体
     */
    public KnowledgeDocumentEntity createNewVersion(Long taskId, String knowledgeType,
            String title, String ragflowDocumentId) {
        return createNewVersion(taskId, knowledgeType, title, ragflowDocumentId, null, null);
    }

    /**
     * 创建新版本文档（含 metadata）。
     * <p>
     * 查找同一 taskId 和 knowledgeType 下未禁用的文档，将它们全部禁用，
     * 然后创建一个新版本（version = 最大已有版本 + 1），状态为 COMPLETED。
     * </p>
     *
     * @param taskId            关联入库任务 ID
     * @param knowledgeType     知识类型（GUIDE/QA）
     * @param title             文档标题
     * @param ragflowDocumentId RAGFlow 文档 ID
     * @param topic             主题（可空）
     * @param tagsJson          标签 JSON（可空）
     * @return 新创建的文档实体
     */
    public KnowledgeDocumentEntity createNewVersion(Long taskId, String knowledgeType,
            String title, String ragflowDocumentId, String topic, String tagsJson) {
        // 查找当前最大版本号
        KnowledgeDocumentEntity latest = getLatestVersion(taskId, knowledgeType);
        int newVersion = (latest != null) ? latest.getVersion() + 1 : 1;

        // 禁用旧版本（先禁用，再创建新版本）
        disableOldVersions(taskId, knowledgeType, null);

        // 创建新版本文档
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocumentEntity newDoc = new KnowledgeDocumentEntity();
        newDoc.setTaskId(taskId);
        newDoc.setKnowledgeType(knowledgeType);
        newDoc.setTitle(title);
        newDoc.setRagflowDocumentId(ragflowDocumentId);
        newDoc.setDatasetName(kbProperties.getRagflow().getDatasetId());
        newDoc.setTopic(topic);
        newDoc.setTagsJson(tagsJson);
        newDoc.setVersion(newVersion);
        newDoc.setStatus(DocumentStatus.COMPLETED.name());
        newDoc.setReviewStatus(ReviewStatus.CANDIDATE.name());
        newDoc.setCreatedAt(now);
        newDoc.setUpdatedAt(now);

        knowledgeDocumentMapper.insert(newDoc);
        log.info("新版本文档已创建: taskId={}, knowledgeType={}, version={}, documentId={}",
                taskId, knowledgeType, newVersion, newDoc.getId());

        return newDoc;
    }

    /**
     * 获取最新版本文档。
     * <p>
     * 查询指定 taskId 和 knowledgeType 下版本号最高的文档。
     * </p>
     *
     * @param taskId        关联入库任务 ID
     * @param knowledgeType 知识类型（GUIDE/QA）
     * @return 最新版本的文档实体，不存在时返回 null
     */
    public KnowledgeDocumentEntity getLatestVersion(Long taskId, String knowledgeType) {
        return knowledgeDocumentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getTaskId, taskId)
                        .eq(KnowledgeDocumentEntity::getKnowledgeType, knowledgeType)
                        .orderByDesc(KnowledgeDocumentEntity::getVersion)
                        .last("LIMIT 1"));
    }

    // ── 内部辅助方法 ──

    /**
     * 禁用旧版本文档。
     * <p>
     * 将同一 taskId 和 knowledgeType 下所有未禁用的文档状态置为 DISABLED。
     * 可选排除指定 documentId（用于在创建新版本后禁用旧版本时排除新版本自身）。
     * </p>
     *
     * @param taskId        关联入库任务 ID
     * @param knowledgeType 知识类型（GUIDE/QA）
     * @param excludeId     排除的文档 ID（可为 null）
     */
    private void disableOldVersions(Long taskId, String knowledgeType, Long excludeId) {
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getTaskId, taskId)
                .eq(KnowledgeDocumentEntity::getKnowledgeType, knowledgeType)
                .ne(KnowledgeDocumentEntity::getStatus, DocumentStatus.DISABLED.name());

        if (excludeId != null) {
            wrapper.ne(KnowledgeDocumentEntity::getId, excludeId);
        }

        List<KnowledgeDocumentEntity> oldDocs = knowledgeDocumentMapper.selectList(wrapper);
        LocalDateTime now = LocalDateTime.now();

        for (KnowledgeDocumentEntity oldDoc : oldDocs) {
            oldDoc.setStatus(DocumentStatus.DISABLED.name());
            oldDoc.setUpdatedAt(now);
            knowledgeDocumentMapper.updateById(oldDoc);
            log.info("旧版本文档已禁用: documentId={}, taskId={}, knowledgeType={}, version={}",
                    oldDoc.getId(), taskId, knowledgeType, oldDoc.getVersion());
        }

        if (!oldDocs.isEmpty()) {
            log.info("共禁用 {} 个旧版本文档: taskId={}, knowledgeType={}", oldDocs.size(), taskId, knowledgeType);
        }
    }

    /**
     * 根据 documentId 查找文档，不存在则抛出 BizException。
     */
    private KnowledgeDocumentEntity findDocumentOrThrow(Long documentId) {
        KnowledgeDocumentEntity doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new BizException("文档不存在: documentId=" + documentId);
        }
        return doc;
    }

    /**
     * 将文档实体转换为详情响应 DTO。
     */
    private DocumentDetailResponse toDetailResponse(KnowledgeDocumentEntity doc) {
        return new DocumentDetailResponse(
                doc.getId(),
                doc.getTaskId(),
                doc.getKnowledgeType(),
                doc.getTitle(),
                doc.getTopic(),
                doc.getTagsJson(),
                doc.getReviewStatus(),
                doc.getStatus(),
                doc.getDatasetName(),
                doc.getRagflowDocumentId(),
                doc.getMetadataJson(),
                doc.getVersion(),
                doc.getCreatedAt(),
                doc.getUpdatedAt());
    }
}
