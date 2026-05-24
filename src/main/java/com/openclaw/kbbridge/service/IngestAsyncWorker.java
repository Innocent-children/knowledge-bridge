package com.openclaw.kbbridge.service;

import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.dto.ingest.Attachment;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.processor.KnowledgeProcessor;
import com.openclaw.kbbridge.processor.ProcessResult;
import com.openclaw.kbbridge.processor.QualityCheckResult;
import com.openclaw.kbbridge.processor.QualityChecker;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.router.IngestRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 入库异步处理工作器。
 * <p>
 * 从 {@link IngestService} 中提取的异步处理逻辑，作为独立的 Spring Bean 存在，
 * 确保 {@code @Async} 注解通过 Spring AOP 代理正确生效（修复 Defect 1：自调用绕过代理问题）。
 * </p>
 * <p>
 * 异步处理流程：
 * <ol>
 * <li>保存原始件到 MinIO → RAW_STORED</li>
 * <li>路由内容类型 → 获取对应处理器</li>
 * <li>LLM 知识化重写 → PROCESSING</li>
 * <li>质量校验 → 失败则保存到 failed/，状态 FAILED</li>
 * <li>保存处理件到 MinIO → COMPLETED</li>
 * <li>创建 kb_document 记录</li>
 * </ol>
 * 任何步骤失败 → FAILED + errorMessage
 * </p>
 */
@Slf4j
@Component
public class IngestAsyncWorker {

    private final IngestTaskMapper ingestTaskMapper;
    private final MinioStorageClient minioStorageClient;
    private final IngestRouter ingestRouter;
    private final QualityChecker qualityChecker;
    private final KbMetrics kbMetrics;
    private final DocumentService documentService;

    /**
     * 构造入库异步处理工作器。
     *
     * @param ingestTaskMapper   入库任务 Mapper
     * @param minioStorageClient MinIO 存储客户端
     * @param ingestRouter       入库内容路由器
     * @param qualityChecker     质量校验器
     * @param kbMetrics          核心指标采集
     * @param documentService    文档管理服务
     */
    public IngestAsyncWorker(IngestTaskMapper ingestTaskMapper,
            MinioStorageClient minioStorageClient,
            IngestRouter ingestRouter,
            QualityChecker qualityChecker,
            KbMetrics kbMetrics,
            DocumentService documentService) {
        this.ingestTaskMapper = ingestTaskMapper;
        this.minioStorageClient = minioStorageClient;
        this.ingestRouter = ingestRouter;
        this.qualityChecker = qualityChecker;
        this.kbMetrics = kbMetrics;
        this.documentService = documentService;
    }

    /**
     * 异步执行入库处理流程。
     * <p>
     * 步骤：
     * <ol>
     * <li>保存原始件到 MinIO → RAW_STORED</li>
     * <li>路由内容类型 → 获取对应处理器</li>
     * <li>LLM 知识化重写 → PROCESSING</li>
     * <li>质量校验 → 失败则保存到 failed/，状态 FAILED</li>
     * <li>保存处理件到 MinIO → COMPLETED</li>
     * <li>创建 kb_document 记录</li>
     * </ol>
     * 任何步骤异常 → FAILED + errorMessage
     * </p>
     *
     * @param taskId      入库任务 ID
     * @param content     原始内容
     * @param attachments 附件列表（可空）
     */
    @Async("ingestTaskExecutor")
    public void processAsync(Long taskId, String content, List<Attachment> attachments) {
        log.info("开始异步处理入库任务: taskId={}", taskId);
        kbMetrics.recordIngestTotal();

        IngestTaskEntity task = ingestTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("入库任务不存在: taskId={}", taskId);
            return;
        }

        try {
            // Step 1: 保存原始件到 MinIO → RAW_STORED
            String rawObjectKey = minioStorageClient.putRawObject(
                    task.getSourceType(), content);
            task.setRawObjectKey(rawObjectKey);
            updateStatus(task, DocumentStatus.RAW_STORED);
            log.info("原始件已保存: taskId={}, rawObjectKey={}", taskId, rawObjectKey);

            // Step 2: 路由内容类型
            KnowledgeProcessor processor = ingestRouter.route(task.getSourceType(), content);

            // Step 3: LLM 知识化重写 → PROCESSING
            updateStatus(task, DocumentStatus.PROCESSING);
            kbMetrics.recordLlmRewriteTotal();
            Map<String, Object> processorContext = new java.util.HashMap<>();
            processorContext.put("requestId", task.getRequestId());
            processorContext.put("userId", task.getUserId());
            if (attachments != null && !attachments.isEmpty()) {
                processorContext.put("attachments", attachments);
            }
            ProcessResult processResult = processor.process(
                    content, task.getSourceType(), processorContext);
            kbMetrics.recordLlmRewriteSuccess();
            task.setProcessorVersion(processResult.processorVersion());
            log.info("LLM 重写完成: taskId={}, processorVersion={}", taskId, processResult.processorVersion());

            // Step 4: 质量校验
            kbMetrics.recordQualityCheckTotal();
            QualityCheckResult qualityResult = qualityChecker.check(
                    content, processResult, task.getSourceType());
            if (!qualityResult.passed()) {
                // 校验失败：保存到 failed/，状态 FAILED
                String failedContent = buildFailedContent(content, processResult, qualityResult);
                minioStorageClient.putFailedObject(failedContent);
                task.setErrorMessage("质量校验失败: " + String.join("; ", qualityResult.failures()));
                updateStatus(task, DocumentStatus.FAILED);
                kbMetrics.recordIngestFailed();
                log.warn("质量校验未通过: taskId={}, failures={}", taskId, qualityResult.failures());
                return;
            }
            kbMetrics.recordQualityCheckPassed();

            // Step 5: 保存处理件到 MinIO → COMPLETED
            if (processResult.guideContent() != null) {
                String guideKey = minioStorageClient.putProcessedGuide(
                        processResult.guideContent(), processResult.topic());
                task.setProcessedGuideKey(guideKey);
            }
            if (processResult.qaContent() != null) {
                String qaKey = minioStorageClient.putProcessedQa(
                        processResult.qaContent(), processResult.topic());
                task.setProcessedQaKey(qaKey);
            }
            updateStatus(task, DocumentStatus.COMPLETED);
            kbMetrics.recordIngestSuccess();
            log.info("入库任务处理完成: taskId={}, status=COMPLETED", taskId);

            // Step 6: 创建 kb_document 记录
            createDocumentRecords(task, processResult);

        } catch (Exception e) {
            log.error("入库任务处理失败: taskId={}, error={}", taskId, e.getMessage(), e);
            task.setErrorMessage(e.getMessage());
            updateStatus(task, DocumentStatus.FAILED);
            kbMetrics.recordIngestFailed();
        }
    }

    // ── 内部辅助方法 ──

    /**
     * 更新任务状态并持久化。
     *
     * @param task   入库任务实体
     * @param status 新状态
     */
    private void updateStatus(IngestTaskEntity task, DocumentStatus status) {
        task.setStatus(status.name());
        task.setUpdatedAt(LocalDateTime.now());
        ingestTaskMapper.updateById(task);
    }

    /**
     * 创建 kb_document 记录，关联入库任务。
     *
     * @param task          入库任务实体
     * @param processResult 处理结果（含 topic 和 tags）
     */
    private void createDocumentRecords(IngestTaskEntity task, ProcessResult processResult) {
        try {
            String topic = processResult.topic();
            String tagsJson = processResult.tags() != null
                    ? com.openclaw.kbbridge.util.JsonUtil.toJsonArray(processResult.tags())
                    : null;

            if (processResult.qaContent() != null) {
                documentService.createNewVersion(
                        task.getId(),
                        "QA",
                        task.getRequestId() + "_qa",
                        null,
                        topic,
                        tagsJson);
                log.info("Q&A 文档记录已创建: taskId={}, topic={}",
                        task.getId(), topic);
            }

            if (processResult.guideContent() != null) {
                documentService.createNewVersion(
                        task.getId(),
                        "GUIDE",
                        task.getRequestId() + "_guide",
                        null,
                        topic,
                        tagsJson);
                log.info("Guide 文档记录已创建: taskId={}, topic={}",
                        task.getId(), topic);
            }
        } catch (Exception e) {
            // 文档记录创建失败不影响入库主流程（已经 COMPLETED）
            log.warn("创建 kb_document 记录失败（不影响入库状态）: taskId={}, error={}",
                    task.getId(), e.getMessage());
        }
    }

    /**
     * 构建质量校验失败时的失败件内容。
     *
     * @param rawContent    原始内容
     * @param processResult 处理结果
     * @param qualityResult 质量校验结果
     * @return 失败件内容
     */
    private String buildFailedContent(String rawContent, ProcessResult processResult,
            QualityCheckResult qualityResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 质量校验失败报告\n\n");
        sb.append("## 失败原因\n");
        for (String failure : qualityResult.failures()) {
            sb.append("- ").append(failure).append("\n");
        }
        sb.append("\n## 原始内容\n");
        sb.append(rawContent != null ? rawContent : "(空)");
        sb.append("\n\n## 重写结果\n");
        if (processResult.guideContent() != null) {
            sb.append("### Guide\n").append(processResult.guideContent()).append("\n");
        }
        if (processResult.qaContent() != null) {
            sb.append("### Q&A\n").append(processResult.qaContent()).append("\n");
        }
        return sb.toString();
    }

}
