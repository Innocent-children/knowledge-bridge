package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.dto.ingest.IngestRequest;
import com.openclaw.kbbridge.dto.ingest.IngestResponse;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.exception.BizException;
import com.openclaw.kbbridge.exception.ValidationException;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.util.HashUtil;
import com.openclaw.kbbridge.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 入库服务。
 * <p>
 * 负责入库任务的创建（同步）和异步处理流程：
 * <ol>
 * <li>创建任务：计算 content_hash、幂等检查、去重检查、创建 IngestTaskEntity</li>
 * <li>异步处理：保存原始件 → 路由 → LLM 重写 → 质量校验 → 保存处理件</li>
 * </ol>
 * 状态流转：RECEIVED → RAW_STORED → PROCESSING → COMPLETED
 * 任何步骤失败 → FAILED + errorMessage
 * </p>
 */
@Slf4j
@Service
public class IngestService {

    private static final Set<String> VALID_SOURCE_TYPES = Set.of(
            "MARKDOWN", "TUTORIAL", "NOTE", "FEISHU_CHAT", "ATTACHMENT");

    private final IngestTaskMapper ingestTaskMapper;
    private final IngestAsyncWorker ingestAsyncWorker;
    private final DocumentService documentService;

    /**
     * 构造入库服务。
     *
     * @param ingestTaskMapper  入库任务 Mapper
     * @param ingestAsyncWorker 入库异步处理工作器
     * @param documentService   文档管理服务
     */
    public IngestService(IngestTaskMapper ingestTaskMapper,
            IngestAsyncWorker ingestAsyncWorker,
            DocumentService documentService) {
        this.ingestTaskMapper = ingestTaskMapper;
        this.ingestAsyncWorker = ingestAsyncWorker;
        this.documentService = documentService;
    }

    /**
     * 创建入库任务（同步部分，由控制器调用）。
     * <p>
     * 流程：
     * <ol>
     * <li>计算 content_hash（SHA-256）</li>
     * <li>requestId 幂等检查：若已存在则返回已有任务</li>
     * <li>content_hash 去重检查（force=false 时）：若重复则返回 duplicate=true</li>
     * <li>创建 IngestTaskEntity（status=RECEIVED, review_status=CANDIDATE）</li>
     * <li>插入数据库</li>
     * <li>触发异步处理</li>
     * <li>返回 IngestResponse</li>
     * </ol>
     * </p>
     *
     * @param request 入库请求
     * @return 入库响应，包含 taskId 和状态
     */
    public IngestResponse createTask(IngestRequest request) {
        String sourceType = request.sourceType();
        if (sourceType == null || !VALID_SOURCE_TYPES.contains(sourceType.toUpperCase())) {
            throw new ValidationException("不支持的sourceType: " + sourceType,
                    request.requestId(), "sourceType");
        }

        String contentHash = HashUtil.sha256(request.content());

        // 1. requestId 幂等检查
        IngestTaskEntity existing = ingestTaskMapper.selectOne(
                new LambdaQueryWrapper<IngestTaskEntity>()
                        .eq(IngestTaskEntity::getRequestId, request.requestId()));
        if (existing != null) {
            log.info("幂等命中，返回已有任务: requestId={}, taskId={}", request.requestId(), existing.getId());
            return new IngestResponse(
                    request.requestId(),
                    existing.getId(),
                    existing.getStatus(),
                    false);
        }

        // 2. content_hash 去重检查（force=false 时）
        if (!request.force()) {
            IngestTaskEntity duplicate = ingestTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestTaskEntity>()
                            .eq(IngestTaskEntity::getContentHash, contentHash)
                            .ne(IngestTaskEntity::getStatus, DocumentStatus.FAILED.name())
                            .last("LIMIT 1"));
            if (duplicate != null) {
                log.info("内容重复，返回已有任务: contentHash={}, taskId={}", contentHash, duplicate.getId());
                return new IngestResponse(
                        request.requestId(),
                        duplicate.getId(),
                        duplicate.getStatus(),
                        true);
            }
        } else {
            // force=true: 查找同 content_hash 的旧任务，禁用其关联的文档（创建新版本时会自动处理）
            IngestTaskEntity oldTask = ingestTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestTaskEntity>()
                            .eq(IngestTaskEntity::getContentHash, contentHash)
                            .eq(IngestTaskEntity::getStatus, DocumentStatus.COMPLETED.name())
                            .last("LIMIT 1"));
            if (oldTask != null) {
                log.info("强制重新入库，旧任务将在新版本创建时自动禁用旧文档: oldTaskId={}", oldTask.getId());
            }
        }

        // 3. 创建入库任务
        LocalDateTime now = LocalDateTime.now();
        IngestTaskEntity task = new IngestTaskEntity();
        task.setRequestId(request.requestId());
        task.setSourceChannel("feishu"); // TODO: 需要按实际情况赋值
        task.setSourceType(request.sourceType());
        task.setUserId(request.userId());
        task.setChatId(request.chatId());
        task.setMessageIdsJson(request.messageIds() != null ? JsonUtil.toJsonArray(request.messageIds()) : null);
        task.setStatus(DocumentStatus.RECEIVED.name());
        task.setReviewStatus(ReviewStatus.CANDIDATE.name());
        task.setContentHash(contentHash);
        task.setRetryCount(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        try {
            ingestTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            log.info("并发幂等命中: requestId={}", request.requestId());
            IngestTaskEntity existingTask = ingestTaskMapper.selectOne(
                    new LambdaQueryWrapper<IngestTaskEntity>()
                            .eq(IngestTaskEntity::getRequestId, request.requestId()));
            if (existingTask != null) {
                return new IngestResponse(request.requestId(), existingTask.getId(),
                        existingTask.getStatus(), false);
            }
            throw new BizException("幂等查询失败: requestId=" + request.requestId());
        }
        log.info("入库任务已创建: taskId={}, requestId={}, sourceType={}",
                task.getId(), request.requestId(), request.sourceType());

        // 4. 触发异步处理（委托给独立 Bean，确保 @Async AOP 代理生效）
        ingestAsyncWorker.processAsync(task.getId(), request.content(), request.attachments());

        // 5. 返回响应
        return new IngestResponse(
                request.requestId(),
                task.getId(),
                DocumentStatus.RECEIVED.name(),
                false);
    }

    /**
     * 根据 taskId 查询入库任务。
     *
     * @param taskId 入库任务 ID
     * @return 入库任务实体，不存在时返回 null
     */
    public IngestTaskEntity getTask(Long taskId) {
        return ingestTaskMapper.selectById(taskId);
    }
}
