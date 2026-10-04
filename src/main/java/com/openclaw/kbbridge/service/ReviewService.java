package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.review.*;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.entity.ReviewTaskEntity;
import com.openclaw.kbbridge.exception.BizException;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.repository.ReviewTaskMapper;
import com.openclaw.kbbridge.unified.UnifiedKnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审核服务。
 * <p>
 * 提供审核通过、审核拒绝、待审核列表查询、审核详情查询、批量审核等功能。
 * 审核通过后状态流转：reviewStatus → APPROVED，status → COMPLETED。
 * 审核拒绝后终止入库流程：reviewStatus → REJECTED。
 * </p>
 */
@Slf4j
@Service
public class ReviewService {

    private static final int CONTENT_PREVIEW_LENGTH = 200;

    private final IngestTaskMapper ingestTaskMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final MinioStorageClient minioStorageClient;
    private final KbProperties kbProperties;
    private UnifiedKnowledgeService unifiedKnowledgeService;
    @Autowired(required = false)
    public void setUnifiedKnowledgeService(UnifiedKnowledgeService service) { this.unifiedKnowledgeService = service; }

    public ReviewService(IngestTaskMapper ingestTaskMapper,
            ReviewTaskMapper reviewTaskMapper,
            MinioStorageClient minioStorageClient,
            KbProperties kbProperties) {
        this.ingestTaskMapper = ingestTaskMapper;
        this.reviewTaskMapper = reviewTaskMapper;
        this.minioStorageClient = minioStorageClient;
        this.kbProperties = kbProperties;
    }

    /**
     * 审核通过。
     * <p>
     * 将入库任务的审核状态更新为 APPROVED，入库状态更新为 COMPLETED，
     * 并创建审核记录。
     * </p>
     *
     * @param request 审核通过请求
     * @throws BizException 任务不存在或当前审核状态不是 CANDIDATE
     */
    public void approve(ReviewApproveRequest request) {
        IngestTaskEntity task = findTaskOrThrow(request.taskId());
        validateCandidateStatus(task);

        if (task.getOperation() != null) {
            if (unifiedKnowledgeService == null) throw new BizException("统一文档服务不可用");
            unifiedKnowledgeService.legacyReview(task.getId(), true);
            createReviewRecord(request.taskId(), ReviewStatus.APPROVED, request.reviewer(), request.comment());
            return;
        }
        task.setReviewStatus(ReviewStatus.APPROVED.name());
        task.setStatus(DocumentStatus.COMPLETED.name());
        task.setUpdatedAt(LocalDateTime.now());
        ingestTaskMapper.updateById(task);

        createReviewRecord(request.taskId(), ReviewStatus.APPROVED, request.reviewer(), request.comment());

        log.info("审核通过: taskId={}, reviewer={}", request.taskId(), request.reviewer());
    }

    /**
     * 审核拒绝。
     * <p>
     * 将入库任务的审核状态更新为 REJECTED，终止入库流程，
     * 并创建审核记录。
     * </p>
     *
     * @param request 审核拒绝请求
     * @throws BizException 任务不存在或当前审核状态不是 CANDIDATE
     */
    public void reject(ReviewRejectRequest request) {
        IngestTaskEntity task = findTaskOrThrow(request.taskId());
        validateCandidateStatus(task);

        if (task.getOperation() != null) {
            if (unifiedKnowledgeService == null) throw new BizException("统一文档服务不可用");
            unifiedKnowledgeService.legacyReview(task.getId(), false);
            createReviewRecord(request.taskId(), ReviewStatus.REJECTED, request.reviewer(), request.comment());
            return;
        }
        task.setReviewStatus(ReviewStatus.REJECTED.name());
        task.setUpdatedAt(LocalDateTime.now());
        ingestTaskMapper.updateById(task);

        createReviewRecord(request.taskId(), ReviewStatus.REJECTED, request.reviewer(), request.comment());

        log.info("审核拒绝: taskId={}, reviewer={}", request.taskId(), request.reviewer());
    }

    /**
     * 分页查询待审核列表。
     *
     * @param page       页码（从 1 开始）
     * @param size       每页大小
     * @param sourceType 来源类型过滤（可选）
     * @return 分页结果
     */
    public Page<ReviewListResponse> getPendingList(int page, int size, String sourceType) {
        LambdaQueryWrapper<IngestTaskEntity> wrapper = new LambdaQueryWrapper<IngestTaskEntity>()
                .eq(IngestTaskEntity::getReviewStatus, ReviewStatus.CANDIDATE.name())
                .orderByDesc(IngestTaskEntity::getCreatedAt);

        if (sourceType != null && !sourceType.isBlank()) {
            wrapper.eq(IngestTaskEntity::getSourceType, sourceType);
        }

        Page<IngestTaskEntity> entityPage = ingestTaskMapper.selectPage(
                new Page<>(page, size), wrapper);

        Page<ReviewListResponse> responsePage = new Page<>(entityPage.getCurrent(),
                entityPage.getSize(), entityPage.getTotal());
        responsePage.setRecords(entityPage.getRecords().stream()
                .map(this::toListResponse)
                .toList());

        return responsePage;
    }

    /**
     * 查询审核详情。
     *
     * @param taskId 入库任务 ID
     * @return 审核详情，任务不存在时返回 null
     */
    public ReviewDetailResponse getDetail(Long taskId) {
        IngestTaskEntity task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }

        String contentPreview = readContentPreview(task);

        return new ReviewDetailResponse(
                task.getId(),
                task.getRequestId(),
                task.getUserId(),
                task.getSourceType(),
                task.getStatus(),
                task.getReviewStatus(),
                task.getRawObjectKey(),
                task.getProcessedGuideKey(),
                task.getProcessedQaKey(),
                contentPreview,
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    /**
     * 批量审核。
     *
     * @param request 批量审核请求
     * @return 批量审核响应
     */
    public ReviewBatchResponse batchReview(ReviewBatchRequest request) {
        List<ReviewBatchResponse.BatchResult> results = new ArrayList<>();

        for (ReviewBatchRequest.BatchItem item : request.items()) {
            try {
                if ("APPROVE".equalsIgnoreCase(item.action())) {
                    approve(new ReviewApproveRequest(item.taskId(), request.reviewer(), item.comment()));
                    results.add(new ReviewBatchResponse.BatchResult(item.taskId(), true, "审核通过"));
                } else if ("REJECT".equalsIgnoreCase(item.action())) {
                    reject(new ReviewRejectRequest(item.taskId(), request.reviewer(), item.comment()));
                    results.add(new ReviewBatchResponse.BatchResult(item.taskId(), true, "审核拒绝"));
                } else {
                    results.add(new ReviewBatchResponse.BatchResult(item.taskId(), false,
                            "不支持的审核动作: " + item.action()));
                }
            } catch (Exception e) {
                log.warn("批量审核失败: taskId={}, error={}", item.taskId(), e.getMessage());
                results.add(new ReviewBatchResponse.BatchResult(item.taskId(), false, e.getMessage()));
            }
        }

        return new ReviewBatchResponse(results);
    }

    // ── 内部辅助方法 ──

    /**
     * 根据 taskId 查找入库任务，不存在则抛出 BizException。
     */
    private IngestTaskEntity findTaskOrThrow(Long taskId) {
        IngestTaskEntity task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("入库任务不存在: taskId=" + taskId);
        }
        return task;
    }

    /**
     * 校验入库任务的审核状态是否为 CANDIDATE。
     */
    private void validateCandidateStatus(IngestTaskEntity task) {
        if (!ReviewStatus.CANDIDATE.name().equals(task.getReviewStatus())) {
            throw new BizException("当前审核状态不允许操作: taskId=" + task.getId()
                    + ", reviewStatus=" + task.getReviewStatus());
        }
    }

    /**
     * 创建审核记录。
     */
    private void createReviewRecord(Long taskId, ReviewStatus reviewStatus,
            String reviewer, String comment) {
        LocalDateTime now = LocalDateTime.now();
        ReviewTaskEntity reviewTask = new ReviewTaskEntity();
        reviewTask.setTaskId(taskId);
        reviewTask.setReviewStatus(reviewStatus.name());
        reviewTask.setReviewer(reviewer);
        reviewTask.setComment(comment);
        reviewTask.setCreatedAt(now);
        reviewTask.setUpdatedAt(now);
        reviewTaskMapper.insert(reviewTask);
    }

    /**
     * 将入库任务实体转换为待审核列表响应 DTO。
     * 列表中不读取 MinIO 内容以避免 N+1 性能问题，contentPreview 返回 null。
     */
    private ReviewListResponse toListResponse(IngestTaskEntity task) {
        return new ReviewListResponse(
                task.getId(),
                task.getRequestId(),
                task.getUserId(),
                task.getSourceType(),
                null,
                task.getReviewStatus(),
                task.getCreatedAt(),
                task.getStatus(),
                task.getOperation());
    }

    /**
     * 从 MinIO 读取原始内容并截取前 200 字作为摘要。
     * 读取失败时返回 null。
     *
     * @param rawObjectKey MinIO 原始件路径
     * @return 内容摘要，或 null
     */
    private String readContentPreview(IngestTaskEntity task) {
        String rawObjectKey = task.getRawObjectKey();
        if (rawObjectKey == null || rawObjectKey.isBlank()) {
            return null;
        }
        try {
            String content = minioStorageClient.getObject(
                    task.getOperation() == null ? kbProperties.getMinio().getRawBucket() : "kb-content", rawObjectKey);
            if (content == null) {
                return null;
            }
            return content.length() > CONTENT_PREVIEW_LENGTH
                    ? content.substring(0, CONTENT_PREVIEW_LENGTH)
                    : content;
        } catch (Exception e) {
            log.warn("读取 MinIO 原始内容失败: rawObjectKey={}, error={}", rawObjectKey, e.getMessage());
            return null;
        }
    }
}
