package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.dto.ingest.IngestResponse;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.model.enums.DocumentStatus;
import com.openclaw.kbbridge.model.enums.ReviewStatus;
import com.openclaw.kbbridge.service.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IngestController 单元测试。
 * <p>
 * 使用 @WebMvcTest 切片测试，通过 @AutoConfigureMockMvc(addFilters = false)
 * 禁用 HmacSignatureFilter，专注测试控制器逻辑。
 * </p>
 */
@WebMvcTest(controllers = IngestController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "logging.level.com.openclaw.kbbridge=INFO",
        "logging.level.com.openclaw.kbbridge.client=DEBUG",
        "logging.level.com.openclaw.kbbridge.security=WARN",
        "logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
})
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestService ingestService;

    @MockitoBean
    private com.openclaw.kbbridge.service.CandidateEvalService candidateEvalService;

    @MockitoBean
    private com.openclaw.kbbridge.repository.IngestTaskMapper ingestTaskMapper;

    /**
     * POST /ingest/manual 有效请求返回 200。
     */
    @Test
    void ingestManual_validRequest_returns200() throws Exception {
        IngestResponse response = new IngestResponse("req-001", 100L, "RECEIVED", false);
        when(ingestService.createTask(any())).thenReturn(response);

        String body = """
                {
                    "requestId": "req-001",
                    "userId": "user-001",
                    "content": "这是一段测试内容",
                    "sourceType": "FEISHU_CHAT",
                    "force": false
                }
                """;

        mockMvc.perform(post("/api/v1/ingest/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-001"))
                .andExpect(jsonPath("$.taskId").value(100))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.duplicate").value(false));

        verify(ingestService, times(1)).createTask(any());
    }

    /**
     * POST /ingest/manual 缺少 requestId 返回 400。
     */
    @Test
    void ingestManual_missingRequestId_returns400() throws Exception {
        String body = """
                {
                    "userId": "user-001",
                    "content": "内容",
                    "sourceType": "FEISHU_CHAT"
                }
                """;

        mockMvc.perform(post("/api/v1/ingest/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());

        verify(ingestService, never()).createTask(any());
    }

    /**
     * POST /ingest/manual 缺少 userId 返回 400。
     */
    @Test
    void ingestManual_missingUserId_returns400() throws Exception {
        String body = """
                {
                    "requestId": "req-001",
                    "content": "内容",
                    "sourceType": "FEISHU_CHAT"
                }
                """;

        mockMvc.perform(post("/api/v1/ingest/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());

        verify(ingestService, never()).createTask(any());
    }

    /**
     * POST /ingest/manual 缺少 content 返回 400。
     */
    @Test
    void ingestManual_missingContent_returns400() throws Exception {
        String body = """
                {
                    "requestId": "req-001",
                    "userId": "user-001",
                    "sourceType": "FEISHU_CHAT"
                }
                """;

        mockMvc.perform(post("/api/v1/ingest/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());

        verify(ingestService, never()).createTask(any());
    }

    /**
     * POST /ingest/manual 缺少 sourceType 返回 400。
     */
    @Test
    void ingestManual_missingSourceType_returns400() throws Exception {
        String body = """
                {
                    "requestId": "req-001",
                    "userId": "user-001",
                    "content": "内容"
                }
                """;

        mockMvc.perform(post("/api/v1/ingest/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());

        verify(ingestService, never()).createTask(any());
    }

    /**
     * GET /ingest/status/{taskId} 存在的任务返回 200。
     */
    @Test
    void getStatus_existingTask_returns200() throws Exception {
        IngestTaskEntity task = new IngestTaskEntity();
        task.setId(100L);
        task.setRequestId("req-001");
        task.setStatus(DocumentStatus.COMPLETED.name());
        task.setReviewStatus(ReviewStatus.CANDIDATE.name());
        task.setRawObjectKey("raw/path/raw.md");
        task.setProcessedGuideKey(null);
        task.setProcessedQaKey("qa/path/qa.md");
        task.setErrorMessage(null);
        task.setCreatedAt(LocalDateTime.of(2025, 1, 15, 10, 30, 0));
        task.setUpdatedAt(LocalDateTime.of(2025, 1, 15, 10, 35, 0));

        when(ingestService.getTask(100L)).thenReturn(task);

        mockMvc.perform(get("/api/v1/ingest/status/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(100))
                .andExpect(jsonPath("$.requestId").value("req-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.reviewStatus").value("CANDIDATE"))
                .andExpect(jsonPath("$.rawObjectKey").value("raw/path/raw.md"))
                .andExpect(jsonPath("$.processedQaKey").value("qa/path/qa.md"));
    }

    /**
     * GET /ingest/status/{taskId} 不存在的任务返回 404。
     */
    @Test
    void getStatus_nonExistentTask_returns404() throws Exception {
        when(ingestService.getTask(999L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/ingest/status/999"))
                .andExpect(status().isNotFound());
    }
}
