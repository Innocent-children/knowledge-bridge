package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.dto.query.RetrievalQuality;
import com.openclaw.kbbridge.entity.QueryLogEntity;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.repository.QueryLogMapper;
import com.openclaw.kbbridge.service.QueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QueryController 单元测试。
 * <p>
 * 使用 @WebMvcTest 切片测试，通过 @AutoConfigureMockMvc(addFilters = false)
 * 禁用 HmacSignatureFilter，专注测试控制器逻辑。
 * </p>
 */
@WebMvcTest(controllers = QueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
                "logging.level.com.openclaw.kbbridge=INFO",
                "logging.level.com.openclaw.kbbridge.client=DEBUG",
                "logging.level.com.openclaw.kbbridge.security=WARN",
                "logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
})
class QueryControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private QueryService queryService;

        @MockitoBean
        private QueryLogMapper queryLogMapper;

        @Test
        void validRequest_returns200WithQueryResponse() throws Exception {
                QueryResponse response = new QueryResponse(
                                "req-001", QueryRoute.KB_PLUS_LLM, false,
                                List.of(), List.of("优先依据 sources 回答"),
                                new RetrievalQuality(0, Confidence.LOW, false, 0));

                when(queryLogMapper.selectOne(any())).thenReturn(null);
                when(queryService.query(any(QueryRequest.class))).thenReturn(response);

                String body = """
                                {
                                    "requestId": "req-001",
                                    "userId": "user-001",
                                    "question": "如何配置数据库？",
                                    "isGroup": false
                                }
                                """;

                mockMvc.perform(post("/api/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.requestId").value("req-001"))
                                .andExpect(jsonPath("$.route").value("KB_PLUS_LLM"))
                                .andExpect(jsonPath("$.allowModelSupplement").value(false));

                verify(queryService, times(1)).query(any(QueryRequest.class));
        }

        @Test
        void missingRequestId_returns400() throws Exception {
                String body = """
                                {
                                    "userId": "user-001",
                                    "question": "如何配置数据库？",
                                    "isGroup": false
                                }
                                """;

                mockMvc.perform(post("/api/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());

                verify(queryService, never()).query(any());
        }

        @Test
        void missingUserId_returns400() throws Exception {
                String body = """
                                {
                                    "requestId": "req-001",
                                    "question": "如何配置数据库？",
                                    "isGroup": false
                                }
                                """;

                mockMvc.perform(post("/api/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());

                verify(queryService, never()).query(any());
        }

        @Test
        void missingQuestion_returns400() throws Exception {
                String body = """
                                {
                                    "requestId": "req-001",
                                    "userId": "user-001",
                                    "isGroup": false
                                }
                                """;

                mockMvc.perform(post("/api/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());

                verify(queryService, never()).query(any());
        }

        @Test
        void duplicateRequestId_returnsCachedResult() throws Exception {
                QueryResponse cachedResponse = new QueryResponse(
                                "req-dup", QueryRoute.LLM_ONLY, false,
                                List.of(), List.of(),
                                new RetrievalQuality(0, Confidence.LOW, false, 0));

                QueryLogEntity existingLog = new QueryLogEntity();
                existingLog.setRequestId("req-dup");
                existingLog.setResponseJson(objectMapper.writeValueAsString(cachedResponse));

                when(queryLogMapper.selectOne(any())).thenReturn(existingLog);

                String body = """
                                {
                                    "requestId": "req-dup",
                                    "userId": "user-001",
                                    "question": "重复请求",
                                    "isGroup": false
                                }
                                """;

                mockMvc.perform(post("/api/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.requestId").value("req-dup"))
                                .andExpect(jsonPath("$.route").value("LLM_ONLY"));

                // 幂等命中时不应调用 QueryService
                verify(queryService, never()).query(any());
        }

        @Test
        void existingLogWithNullResponseJson_proceedsWithQuery() throws Exception {
                QueryLogEntity existingLog = new QueryLogEntity();
                existingLog.setRequestId("req-progress");
                existingLog.setResponseJson(null);

                QueryResponse response = new QueryResponse(
                                "req-progress", QueryRoute.KB_PLUS_LLM, false,
                                List.of(), List.of(),
                                new RetrievalQuality(0, Confidence.LOW, false, 0));

                when(queryLogMapper.selectOne(any())).thenReturn(existingLog);
                when(queryService.query(any(QueryRequest.class))).thenReturn(response);

                String body = """
                                {
                                    "requestId": "req-progress",
                                    "userId": "user-001",
                                    "question": "进行中的请求",
                                    "isGroup": false
                                }
                                """;

                mockMvc.perform(post("/api/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.requestId").value("req-progress"));

                // responseJson 为 null 时应继续执行查询
                verify(queryService, times(1)).query(any(QueryRequest.class));
        }

        @Test
        void existingLogWithBlankResponseJson_proceedsWithQuery() throws Exception {
                QueryLogEntity existingLog = new QueryLogEntity();
                existingLog.setRequestId("req-blank");
                existingLog.setResponseJson("   ");

                QueryResponse response = new QueryResponse(
                                "req-blank", QueryRoute.KB_PLUS_LLM, false,
                                List.of(), List.of(),
                                new RetrievalQuality(0, Confidence.LOW, false, 0));

                when(queryLogMapper.selectOne(any())).thenReturn(existingLog);
                when(queryService.query(any(QueryRequest.class))).thenReturn(response);

                String body = """
                                {
                                    "requestId": "req-blank",
                                    "userId": "user-001",
                                    "question": "空白响应的请求",
                                    "isGroup": false
                                }
                                """;

                mockMvc.perform(post("/api/v1/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isOk());

                verify(queryService, times(1)).query(any(QueryRequest.class));
        }
        @Test
        void unifiedRequest_rechecksInsteadOfReturningStaleCachedEvidence() throws Exception {
                QueryResponse cached = new QueryResponse("req", QueryRoute.KB_ONLY, false,
                                List.of(new com.openclaw.kbbridge.dto.query.EvidenceSource("unified", "old", "withdrawn content", .9, java.util.Map.of())),
                                List.of(), new RetrievalQuality(1, Confidence.HIGH, false, 1));
                QueryLogEntity existing = new QueryLogEntity();
                existing.setResponseJson(objectMapper.writeValueAsString(cached));
                when(queryLogMapper.selectOne(any())).thenReturn(existing);
                QueryResponse current = new QueryResponse("req", QueryRoute.KB_ONLY, false, List.of(), List.of(),
                                new RetrievalQuality(0, Confidence.LOW, false, 0));
                when(queryService.usesUnifiedKnowledge()).thenReturn(true);
                when(queryService.query(any(QueryRequest.class))).thenReturn(current);
                mockMvc.perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"req\",\"userId\":\"user\",\"question\":\"question\",\"isGroup\":false}"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.sources").isEmpty());
                verify(queryService).query(any(QueryRequest.class));
        }

}
