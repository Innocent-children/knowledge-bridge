package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.dto.query.RetrievalQuality;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.service.QueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatController 单元测试。
 * <p>
 * 使用 @WebMvcTest 切片测试，通过 @AutoConfigureMockMvc(addFilters = false)
 * 禁用 HmacSignatureFilter 和 RateLimitFilter，专注测试控制器逻辑。
 * </p>
 * <p>
 * Requirements: 5.1, 5.5, 5.6, 5.7
 * </p>
 */
@WebMvcTest(controllers = ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
                "logging.level.com.openclaw.kbbridge=INFO",
                "logging.level.com.openclaw.kbbridge.security=WARN",
                "logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
})
class ChatControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private QueryService queryService;

        @MockitoBean
        private LlmClient llmClient;

        /**
         * 有效请求返回完整的 ChatResponse，包含 answer、route、sources，且 llmError 为 false。
         * Validates: Requirements 5.1, 5.5
         */
        @Test
        void validRequest_returnsCompleteChatResponse() throws Exception {
                List<EvidenceSource> sources = List.of(
                                new EvidenceSource("kb_qa", "配置指南", "数据库配置步骤如下...", 0.85, Map.of()));

                QueryResponse queryResponse = new QueryResponse(
                                "req-001", QueryRoute.KB_PLUS_LLM, false,
                                sources, List.of("优先依据 sources 回答"),
                                new RetrievalQuality(1, Confidence.HIGH, false, 1));

                when(queryService.query(any(QueryRequest.class))).thenReturn(queryResponse);
                when(llmClient.complete(any(), any())).thenReturn("根据知识库，数据库配置步骤如下...");

                String body = """
                                {"question": "如何配置数据库？"}
                                """;

                mockMvc.perform(post("/api/v1/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.answer").value("根据知识库，数据库配置步骤如下..."))
                                .andExpect(jsonPath("$.route").value("KB_PLUS_LLM"))
                                .andExpect(jsonPath("$.sources").isArray())
                                .andExpect(jsonPath("$.sources.length()").value(1))
                                .andExpect(jsonPath("$.sources[0].title").value("配置指南"))
                                .andExpect(jsonPath("$.llmError").value(false))
                                .andExpect(jsonPath("$.errorMessage").doesNotExist());

                verify(queryService).query(any(QueryRequest.class));
                verify(llmClient).complete(any(), any());
        }

        /**
         * 空白 question 字段触发 Jakarta 校验，返回 400 Bad Request。
         * Validates: Requirements 5.1
         */
        @Test
        void blankQuestion_returns400() throws Exception {
                String body = """
                                {"question": "   "}
                                """;

                mockMvc.perform(post("/api/v1/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());

                verify(queryService, never()).query(any());
                verify(llmClient, never()).complete(any(), any());
        }

        /**
         * 缺少 question 字段触发校验，返回 400 Bad Request。
         * Validates: Requirements 5.1
         */
        @Test
        void missingQuestion_returns400() throws Exception {
                String body = """
                                {}
                                """;

                mockMvc.perform(post("/api/v1/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());

                verify(queryService, never()).query(any());
                verify(llmClient, never()).complete(any(), any());
        }

        /**
         * QueryService 抛出 ExternalServiceException 时返回 HTTP 502。
         * Validates: Requirements 5.6
         */
        @Test
        void queryServiceFailure_returns502() throws Exception {
                when(queryService.query(any(QueryRequest.class)))
                                .thenThrow(new ExternalServiceException(
                                                "RAGFlow 连接超时", null, "RAGFlow", 504));

                String body = """
                                {"question": "如何配置数据库？"}
                                """;

                mockMvc.perform(post("/api/v1/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadGateway())
                                .andExpect(jsonPath("$.answer").doesNotExist())
                                .andExpect(jsonPath("$.llmError").value(false))
                                .andExpect(jsonPath("$.errorMessage").exists())
                                .andExpect(jsonPath("$.sources").isArray())
                                .andExpect(jsonPath("$.sources.length()").value(0));

                verify(queryService).query(any(QueryRequest.class));
                verify(llmClient, never()).complete(any(), any());
        }

        /**
         * LlmClient 抛出异常时返回降级响应：包含 sources，llmError=true，answer 为 null。
         * Validates: Requirements 5.7
         */
        @Test
        void llmClientFailure_returnsDegradedResponseWithSourcesAndLlmError() throws Exception {
                List<EvidenceSource> sources = List.of(
                                new EvidenceSource("kb_qa", "部署文档", "部署步骤说明...", 0.9, Map.of()),
                                new EvidenceSource("kb_guide", "运维手册", "运维注意事项...", 0.75, Map.of()));

                QueryResponse queryResponse = new QueryResponse(
                                "req-002", QueryRoute.KB_ONLY, false,
                                sources, List.of("只依据知识证据回答"),
                                new RetrievalQuality(2, Confidence.HIGH, false, 2));

                when(queryService.query(any(QueryRequest.class))).thenReturn(queryResponse);
                when(llmClient.complete(any(), any()))
                                .thenThrow(new RuntimeException("LLM API 连接失败"));

                String body = """
                                {"question": "如何部署系统？"}
                                """;

                mockMvc.perform(post("/api/v1/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.answer").doesNotExist())
                                .andExpect(jsonPath("$.route").value("KB_ONLY"))
                                .andExpect(jsonPath("$.sources").isArray())
                                .andExpect(jsonPath("$.sources.length()").value(2))
                                .andExpect(jsonPath("$.sources[0].title").value("部署文档"))
                                .andExpect(jsonPath("$.sources[1].title").value("运维手册"))
                                .andExpect(jsonPath("$.llmError").value(true))
                                .andExpect(jsonPath("$.errorMessage").exists());

                verify(queryService).query(any(QueryRequest.class));
                verify(llmClient).complete(any(), any());
        }
}
