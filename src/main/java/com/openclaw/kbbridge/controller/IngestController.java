package com.openclaw.kbbridge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openclaw.kbbridge.dto.ingest.*;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.service.CandidateEvalService;
import com.openclaw.kbbridge.service.IngestService;
import com.openclaw.kbbridge.unified.UnifiedKnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 入库控制器。
 * <p>
 * 提供手动入库、自动候选评估、入库状态查询和入库任务列表端点：
 * <ul>
 * <li>POST /api/v1/ingest/manual — 提交手动入库请求</li>
 * <li>POST /api/v1/ingest/candidate — 自动候选评估</li>
 * <li>GET /api/v1/ingest/status/{taskId} — 查询入库任务状态</li>
 * <li>GET /api/v1/ingest/tasks — 分页查询入库任务列表</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private final IngestService ingestService;
    private final CandidateEvalService candidateEvalService;
    private final IngestTaskMapper ingestTaskMapper;
    private UnifiedKnowledgeService unifiedKnowledgeService;

    @Autowired(required = false)
    public void setUnifiedKnowledgeService(UnifiedKnowledgeService service) { this.unifiedKnowledgeService = service; }

    /**
     * 构造入库控制器。
     *
     * @param ingestService        入库服务
     * @param candidateEvalService 自动候选评估服务
     * @param ingestTaskMapper     入库任务 Mapper
     */
    public IngestController(IngestService ingestService,
                            CandidateEvalService candidateEvalService,
                            IngestTaskMapper ingestTaskMapper) {
        this.ingestService = ingestService;
        this.candidateEvalService = candidateEvalService;
        this.ingestTaskMapper = ingestTaskMapper;
    }

    /**
     * 入库任务分页列表端点。
     * <p>
     * 支持按 status 和 reviewStatus 可选过滤，返回分页结果。
     * </p>
     *
     * @param page         页码（默认 1）
     * @param size         每页大小（默认 20）
     * @param status       入库状态过滤（可选）
     * @param reviewStatus 审核状态过滤（可选）
     * @return 分页入库任务列表
     */
    @GetMapping("/ingest/tasks")
    public ResponseEntity<Page<IngestTaskEntity>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reviewStatus) {
        log.info("查询入库任务列表: page={}, size={}, status={}, reviewStatus={}", page, size, status, reviewStatus);
        Page<IngestTaskEntity> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<IngestTaskEntity> wrapper = new LambdaQueryWrapper<IngestTaskEntity>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(IngestTaskEntity::getStatus, status);
        }
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            wrapper.eq(IngestTaskEntity::getReviewStatus, reviewStatus);
        }
        wrapper.orderByDesc(IngestTaskEntity::getCreatedAt);
        Page<IngestTaskEntity> result = ingestTaskMapper.selectPage(pageParam, wrapper);
        return ResponseEntity.ok(result);
    }

    /**
     * 手动入库端点。
     * <p>
     * 接收入库请求，执行参数校验、requestId 幂等检查、content_hash 去重检查，
     * 创建入库任务并触发异步处理，立即返回 taskId。
     * </p>
     *
     * @param request 入库请求（requestId、userId、content、sourceType 必填）
     * @return 入库响应，包含 taskId 和状态
     */
    @PostMapping("/ingest/manual")
    public ResponseEntity<IngestResponse> ingestManual(@Valid @RequestBody IngestRequest request) {
        log.info("收到入库请求: requestId={}, userId={}, sourceType={}",
                request.requestId(), request.userId(), request.sourceType());
        IngestResponse response = unifiedKnowledgeService != null
                ? unifiedKnowledgeService.legacyIngest(request)
                : ingestService.createTask(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 自动候选评估端点。
     * <p>
     * OpenClaw 转发普通消息时调用此接口，由 Knowledge Bridge 判定内容是否值得沉淀。
     * 判定通过后自动创建入库任务（状态 CANDIDATE）。
     * </p>
     *
     * @param request 候选评估请求
     * @return 评估结果
     */
    @PostMapping("/ingest/candidate")
    public ResponseEntity<CandidateEvalResponse> evaluateCandidate(
            @Valid @RequestBody CandidateEvalRequest request) {
        log.info("收到自动候选评估请求: requestId={}, userId={}, sourceType={}",
                request.requestId(), request.userId(), request.sourceType());
        CandidateEvalResponse response = candidateEvalService.evaluate(request);
        log.info("自动候选评估完成: requestId={}, worthy={}, reason={}",
                request.requestId(), response.worthy(), response.reason());
        return ResponseEntity.ok(response);
    }

    /**
     * 入库状态查询端点。
     * <p>
     * 根据 taskId 查询入库任务的处理进度。
     * taskId 不存在时返回 404。
     * </p>
     *
     * @param taskId 入库任务 ID
     * @return 入库状态响应
     */
    @GetMapping("/ingest/status/{taskId}")
    public ResponseEntity<IngestStatusResponse> getStatus(@PathVariable Long taskId) {
        log.info("查询入库状态: taskId={}", taskId);
        IngestTaskEntity task = unifiedKnowledgeService != null
                ? unifiedKnowledgeService.legacyTask(taskId)
                : ingestService.getTask(taskId);
        if (task == null) {
            log.warn("入库任务不存在: taskId={}", taskId);
            return ResponseEntity.notFound().build();
        }
        IngestStatusResponse response = new IngestStatusResponse(
                task.getId(),
                task.getRequestId(),
                task.getStatus(),
                task.getReviewStatus(),
                task.getRawObjectKey(),
                task.getProcessedGuideKey(),
                task.getProcessedQaKey(),
                task.getFileName(),
                task.getMimeType(),
                task.getFileSize(),
                task.getExternalDocumentId(),
                task.getExternalJobId(),
                task.getErrorCode(),
                task.getRetryable(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt());
        return ResponseEntity.ok(response);
    }
}
