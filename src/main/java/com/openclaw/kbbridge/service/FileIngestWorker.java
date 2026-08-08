package com.openclaw.kbbridge.service;

import com.openclaw.kbbridge.client.KbVectorClient;
import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.processor.KnowledgeProcessor;
import com.openclaw.kbbridge.processor.ProcessResult;
import com.openclaw.kbbridge.processor.QualityCheckResult;
import com.openclaw.kbbridge.processor.QualityChecker;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.router.IngestRouter;
import com.openclaw.kbbridge.util.JsonUtil;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FileIngestWorker {
    private static final int CHUNK_SIZE = 2000;
    private final IngestTaskMapper taskMapper;
    private final MinioStorageClient storage;
    private final FileTextExtractor extractor;
    private final IngestRouter router;
    private final QualityChecker qualityChecker;
    private final KbVectorClient kbVectorClient;
    private final DocumentService documentService;

    public FileIngestWorker(IngestTaskMapper taskMapper, MinioStorageClient storage,
                            FileTextExtractor extractor, IngestRouter router,
                            QualityChecker qualityChecker, KbVectorClient kbVectorClient,
                            DocumentService documentService) {
        this.taskMapper = taskMapper;
        this.storage = storage;
        this.extractor = extractor;
        this.router = router;
        this.qualityChecker = qualityChecker;
        this.kbVectorClient = kbVectorClient;
        this.documentService = documentService;
    }

    @Async("ingestTaskExecutor")
    public void processAsync(Long taskId) {
        IngestTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        try {
            byte[] bytes = storage.getRawBytes(task.getRawObjectKey());
            String text = extractor.extract(task.getFileName(), bytes);
            update(task, DocumentStatus.PROCESSING);
            KnowledgeProcessor processor = router.route("ATTACHMENT", text);
            Map<String, Object> context = new HashMap<>();
            context.put("requestId", task.getRequestId());
            context.put("userId", task.getUserId());
            context.put("fileName", task.getFileName());
            ProcessResult result = processor.process(text, "ATTACHMENT", context);
            QualityCheckResult quality = qualityChecker.check(text, result, "ATTACHMENT");
            if (!quality.passed()) {
                fail(task, "QUALITY_FAILED", "质量校验失败: " + String.join("; ", quality.failures()), false);
                return;
            }
            task.setProcessorVersion(result.processorVersion());
            update(task, DocumentStatus.INDEXING);
            publish(task, result);
            update(task, DocumentStatus.COMPLETED);
        } catch (IllegalArgumentException e) {
            fail(task, "EXTRACTION_FAILED", e.getMessage(), false);
        } catch (Exception e) {
            fail(task, task.getStatus().equals(DocumentStatus.INDEXING.name())
                    ? "INDEXING_FAILED" : "PROCESSING_FAILED", e.getMessage(), true);
        }
    }

    private void publish(IngestTaskEntity task, ProcessResult result) {
        List<Output> outputs = new ArrayList<>();
        if (result.qaContent() != null && !result.qaContent().isBlank()) {
            outputs.add(new Output("QA", result.qaContent()));
        }
        if (result.guideContent() != null && !result.guideContent().isBlank()) {
            outputs.add(new Output("GUIDE", result.guideContent()));
        }
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("处理器未生成可发布内容");
        }
        for (Output output : outputs) {
            String key = task.getId() + ":" + output.type + ":" + result.processorVersion();
            String metadata = "{\"requestId\":\"" + JsonUtil.escapeJson(task.getRequestId())
                    + "\",\"fileHash\":\"" + JsonUtil.escapeJson(task.getContentHash())
                    + "\",\"rawObjectKey\":\"" + JsonUtil.escapeJson(task.getRawObjectKey())
                    + "\",\"sourceMessageId\":\"" + JsonUtil.escapeJson(task.getSourceMessageId())
                    + "\"}";
            KbVectorClient.CreateResult created = kbVectorClient.createDocument(
                    result.topic() != null ? result.topic() : task.getFileName(),
                    chunks(output.content, metadata), key);
            task.setExternalDocumentId(append(task.getExternalDocumentId(), created.document_id()));
            task.setExternalJobId(append(task.getExternalJobId(), created.job_id()));
            taskMapper.updateById(task);
            documentService.createNewVersion(task.getId(), output.type,
                    created.document_id(), null, result.topic(),
                    result.tags() == null ? null : JsonUtil.toJsonArray(result.tags()));
        }
    }

    private List<KbVectorClient.Chunk> chunks(String content, String metadata) {
        List<KbVectorClient.Chunk> chunks = new ArrayList<>();
        for (int start = 0; start < content.length(); start += CHUNK_SIZE) {
            String part = content.substring(start, Math.min(content.length(), start + CHUNK_SIZE)).trim();
            if (!part.isEmpty()) {
                chunks.add(new KbVectorClient.Chunk(part, metadata));
            }
        }
        return chunks;
    }

    private String append(String existing, String value) {
        return existing == null || existing.isBlank() ? value : existing + "," + value;
    }

    private void update(IngestTaskEntity task, DocumentStatus status) {
        task.setStatus(status.name());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void fail(IngestTaskEntity task, String code, String message, boolean retryable) {
        task.setErrorCode(code);
        task.setErrorMessage(message == null ? code : message);
        task.setRetryable(retryable);
        update(task, DocumentStatus.FAILED);
    }

    private record Output(String type, String content) {
    }
}
