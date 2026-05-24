package com.openclaw.kbbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.dto.ingest.IngestRequest;
import com.openclaw.kbbridge.dto.ingest.IngestResponse;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.processor.KnowledgeProcessor;
import com.openclaw.kbbridge.processor.ProcessResult;
import com.openclaw.kbbridge.processor.QualityCheckResult;
import com.openclaw.kbbridge.processor.QualityChecker;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.router.IngestRouter;
import com.openclaw.kbbridge.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IngestService 单元测试。
 * <p>
 * 使用 Mockito 模拟所有外部依赖，验证入库任务创建和异步处理逻辑。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceTest {

        @Mock
        private IngestTaskMapper ingestTaskMapper;
        @Mock
        private MinioStorageClient minioStorageClient;
        @Mock
        private IngestRouter ingestRouter;
        @Mock
        private QualityChecker qualityChecker;
        @Mock
        private KnowledgeProcessor knowledgeProcessor;
        @Mock
        private KbMetrics kbMetrics;
        @Mock
        private IngestAsyncWorker ingestAsyncWorker;
        @Mock
        private DocumentService documentService;

        private IngestService ingestService;

        @BeforeEach
        void setUp() {
                ingestService = new IngestService(ingestTaskMapper, ingestAsyncWorker, documentService);
        }

        /**
         * 新 requestId 创建任务并返回 taskId。
         */
        @Test
        void createTask_newRequestId_createsTaskAndReturnsTaskId() {
                IngestRequest request = new IngestRequest(
                                "req-001", "user-001", "chat-001", List.of("msg-1"),
                                "测试内容", "FEISHU_CHAT", null, false);

                when(ingestTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
                doAnswer(invocation -> {
                        IngestTaskEntity entity = invocation.getArgument(0);
                        entity.setId(100L);
                        return 1;
                }).when(ingestTaskMapper).insert(any(IngestTaskEntity.class));

                IngestResponse response = ingestService.createTask(request);

                assertNotNull(response);
                assertEquals("req-001", response.requestId());
                assertEquals(100L, response.taskId());
                assertEquals(DocumentStatus.RECEIVED.name(), response.status());
                assertFalse(response.duplicate());

                // 验证插入了任务
                ArgumentCaptor<IngestTaskEntity> captor = ArgumentCaptor.forClass(IngestTaskEntity.class);
                verify(ingestTaskMapper).insert(captor.capture());
                IngestTaskEntity inserted = captor.getValue();
                assertEquals("req-001", inserted.getRequestId());
                assertEquals("FEISHU_CHAT", inserted.getSourceType());
                assertEquals(DocumentStatus.RECEIVED.name(), inserted.getStatus());
                assertEquals(HashUtil.sha256("测试内容"), inserted.getContentHash());

                // 验证异步处理被委托给 IngestAsyncWorker
                verify(ingestAsyncWorker).processAsync(eq(100L), eq("测试内容"), isNull());
        }

        /**
         * 重复 requestId 返回已有 taskId（幂等）。
         */
        @Test
        void createTask_duplicateRequestId_returnsExistingTaskId() {
                IngestRequest request = new IngestRequest(
                                "req-dup", "user-001", null, null,
                                "内容", "FEISHU_CHAT", null, false);

                IngestTaskEntity existing = new IngestTaskEntity();
                existing.setId(50L);
                existing.setRequestId("req-dup");
                existing.setStatus(DocumentStatus.COMPLETED.name());

                // 第一次 selectOne 是幂等检查（按 requestId）
                when(ingestTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

                IngestResponse response = ingestService.createTask(request);

                assertEquals("req-dup", response.requestId());
                assertEquals(50L, response.taskId());
                assertEquals(DocumentStatus.COMPLETED.name(), response.status());
                assertFalse(response.duplicate());

                // 不应插入新任务
                verify(ingestTaskMapper, never()).insert(any(IngestTaskEntity.class));
                // 不应触发异步处理
                verify(ingestAsyncWorker, never()).processAsync(anyLong(), anyString(), any());
        }

        /**
         * 重复 content_hash（force=false）返回 duplicate=true。
         */

        @Test
        void createTask_duplicateContentHash_returnsDuplicate() {
                IngestRequest request = new IngestRequest(
                                "req-new", "user-001", null, null,
                                "重复内容", "FEISHU_CHAT", null, false);

                IngestTaskEntity duplicateTask = new IngestTaskEntity();
                duplicateTask.setId(30L);
                duplicateTask.setRequestId("req-old");
                duplicateTask.setStatus(DocumentStatus.COMPLETED.name());

                // 第一次 selectOne（requestId 幂等检查）返回 null
                // 第二次 selectOne（content_hash 去重检查）返回已有任务
                when(ingestTaskMapper.selectOne(any(LambdaQueryWrapper.class)))
                                .thenReturn(null)
                                .thenReturn(duplicateTask);

                IngestResponse response = ingestService.createTask(request);

                assertEquals("req-new", response.requestId());
                assertEquals(30L, response.taskId());
                assertTrue(response.duplicate());

                verify(ingestTaskMapper, never()).insert(any(IngestTaskEntity.class));
        }

        /**
         * force=true 跳过去重检查。
         */
        @Test
        void createTask_forceTrue_skipsDedupCheck() {
                IngestRequest request = new IngestRequest(
                                "req-force", "user-001", null, null,
                                "重复内容", "FEISHU_CHAT", null, true);

                // requestId 幂等检查返回 null，force=true 旧任务查询也返回 null
                when(ingestTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
                doAnswer(invocation -> {
                        IngestTaskEntity entity = invocation.getArgument(0);
                        entity.setId(200L);
                        return 1;
                }).when(ingestTaskMapper).insert(any(IngestTaskEntity.class));

                IngestResponse response = ingestService.createTask(request);

                assertEquals(200L, response.taskId());
                assertFalse(response.duplicate());

                // selectOne 调用两次：1次 requestId 幂等检查 + 1次 force=true 旧任务查询
                verify(ingestTaskMapper, times(2)).selectOne(any(LambdaQueryWrapper.class));
                verify(ingestTaskMapper).insert(any(IngestTaskEntity.class));
        }

        /**
         * 异步处理成功流程：状态依次流转到 COMPLETED。
         * <p>
         * 注意：processAsync 已移至 IngestAsyncWorker，此测试直接测试 worker。
         * </p>
         */
        @Test
        void processAsync_successfulFlow_updatesStatusToCompleted() {
                Long taskId = 1L;
                String content = "测试内容";

                IngestTaskEntity task = new IngestTaskEntity();
                task.setId(taskId);
                task.setRequestId("req-001");
                task.setSourceType("FEISHU_CHAT");
                task.setUserId("user-001");
                task.setStatus(DocumentStatus.RECEIVED.name());

                when(ingestTaskMapper.selectById(taskId)).thenReturn(task);
                when(minioStorageClient.putRawObject(anyString(), anyString()))
                                .thenReturn("raw/path/raw.md");
                when(ingestRouter.route(anyString(), anyString())).thenReturn(knowledgeProcessor);
                when(knowledgeProcessor.process(anyString(), anyString(), anyMap()))
                                .thenReturn(new ProcessResult(null, "## Q1\n问题\n答案", "v1"));
                when(qualityChecker.check(anyString(), any(ProcessResult.class), anyString()))
                                .thenReturn(new QualityCheckResult(true, List.of()));
                when(minioStorageClient.putProcessedQa(anyString(), any()))
                                .thenReturn("qa/path/qa.md");

                // Test IngestAsyncWorker directly
                IngestAsyncWorker worker = new IngestAsyncWorker(
                                ingestTaskMapper, minioStorageClient, ingestRouter,
                                qualityChecker, kbMetrics, documentService);
                worker.processAsync(taskId, content, null);

                // 验证 updateById 被调用了至少 3 次（每次状态变更一次）
                verify(ingestTaskMapper, atLeast(3)).updateById(any(IngestTaskEntity.class));

                // 验证最终状态为 COMPLETED
                assertEquals(DocumentStatus.COMPLETED.name(), task.getStatus());
                assertNotNull(task.getRawObjectKey());
                assertNotNull(task.getProcessedQaKey());
        }

        /**
         * 异步处理失败：设置 FAILED 状态和 errorMessage。
         */
        @Test
        void processAsync_failure_setsFailed() {
                Long taskId = 2L;
                String content = "测试内容";

                IngestTaskEntity task = new IngestTaskEntity();
                task.setId(taskId);
                task.setRequestId("req-002");
                task.setSourceType("FEISHU_CHAT");
                task.setUserId("user-001");
                task.setStatus(DocumentStatus.RECEIVED.name());

                when(ingestTaskMapper.selectById(taskId)).thenReturn(task);
                when(minioStorageClient.putRawObject(anyString(), anyString()))
                                .thenThrow(new ExternalServiceException("MinIO 上传失败", "req-002", "MinIO", null));

                // Test IngestAsyncWorker directly
                IngestAsyncWorker worker = new IngestAsyncWorker(
                                ingestTaskMapper, minioStorageClient, ingestRouter,
                                qualityChecker, kbMetrics, documentService);
                worker.processAsync(taskId, content, null);

                // 验证最终状态为 FAILED
                ArgumentCaptor<IngestTaskEntity> captor = ArgumentCaptor.forClass(IngestTaskEntity.class);
                verify(ingestTaskMapper, atLeastOnce()).updateById(captor.capture());

                IngestTaskEntity lastUpdate = captor.getAllValues().getLast();
                assertEquals(DocumentStatus.FAILED.name(), lastUpdate.getStatus());
                assertNotNull(lastUpdate.getErrorMessage());
        }

        /**
         * 质量校验失败：保存到 failed/，状态 FAILED。
         */
        @Test
        void processAsync_qualityCheckFailed_setsFailed() {
                Long taskId = 3L;
                String content = "测试内容";

                IngestTaskEntity task = new IngestTaskEntity();
                task.setId(taskId);
                task.setRequestId("req-003");
                task.setSourceType("FEISHU_CHAT");
                task.setUserId("user-001");
                task.setStatus(DocumentStatus.RECEIVED.name());

                when(ingestTaskMapper.selectById(taskId)).thenReturn(task);
                when(minioStorageClient.putRawObject(anyString(), anyString()))
                                .thenReturn("raw/path/raw.md");
                when(ingestRouter.route(anyString(), anyString())).thenReturn(knowledgeProcessor);
                when(knowledgeProcessor.process(anyString(), anyString(), anyMap()))
                                .thenReturn(new ProcessResult(null, "短内容", "v1"));
                when(qualityChecker.check(anyString(), any(ProcessResult.class), anyString()))
                                .thenReturn(new QualityCheckResult(false, List.of("内容过短")));
                when(minioStorageClient.putFailedObject(anyString()))
                                .thenReturn("failed/path/failed.md");

                // Test IngestAsyncWorker directly
                IngestAsyncWorker worker = new IngestAsyncWorker(
                                ingestTaskMapper, minioStorageClient, ingestRouter,
                                qualityChecker, kbMetrics, documentService);
                worker.processAsync(taskId, content, null);

                // 验证最终状态为 FAILED
                ArgumentCaptor<IngestTaskEntity> captor = ArgumentCaptor.forClass(IngestTaskEntity.class);
                verify(ingestTaskMapper, atLeastOnce()).updateById(captor.capture());

                IngestTaskEntity lastUpdate = captor.getAllValues().getLast();
                assertEquals(DocumentStatus.FAILED.name(), lastUpdate.getStatus());
                assertTrue(lastUpdate.getErrorMessage().contains("质量校验失败"));

                // 验证保存了失败件
                verify(minioStorageClient).putFailedObject(anyString());
        }
}
