package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmClientImpl 单元测试。
 * 使用 MockWebServer 模拟 OpenAI 兼容的 LLM HTTP 服务。
 */
class LlmClientImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer mockWebServer;
    private LlmClientImpl client;
    private KbProperties kbProperties;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        kbProperties = new KbProperties();
        kbProperties.getProcessor().setLlmBaseUrl(mockWebServer.url("/").toString());
        kbProperties.getProcessor().setLlmApiKey("test-llm-key");
        kbProperties.getProcessor().setLlmModel("gpt-4o");
        kbProperties.getProcessor().setLlmTimeoutMs(5000); // 测试中使用短超时
        // 主测试用例使用非流式响应；流式相关用例在专门的方法里覆盖
        kbProperties.getProcessor().setStreamEnabled(false);

        client = new LlmClientImpl(WebClient.builder(), kbProperties);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void complete_success_returnsContentString() throws Exception {
        String responseBody = """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": "这是 LLM 的回答"
                            },
                            "finishReason": "stop"
                        }
                    ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"));

        String result = client.complete("你是一个助手", "什么是 Spring Boot?");

        assertEquals("这是 LLM 的回答", result);
    }

    @Test
    void complete_httpError_throwsExternalServiceExceptionWithLlmServiceName() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": {\"message\": \"internal server error\"}}")
                .addHeader("Content-Type", "application/json"));

        ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                () -> client.complete("system", "user prompt"));

        assertEquals("LLM", ex.getServiceName());
        assertNotNull(ex.getStatusCode());
        assertEquals(500, ex.getStatusCode());
    }

    @Test
    void complete_httpError_noRetry() {
        // 只入队一个错误响应——如果有重试会因为队列空而报错
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\": {\"message\": \"rate limited\"}}")
                .addHeader("Content-Type", "application/json"));

        assertThrows(ExternalServiceException.class,
                () -> client.complete("system", "user prompt"));

        // 验证只发送了 1 次请求（无重试）
        assertEquals(1, mockWebServer.getRequestCount());
    }

    @Test
    void complete_timeout_throwsExternalServiceException() {
        // 配置极短超时的客户端
        kbProperties.getProcessor().setLlmTimeoutMs(100);
        kbProperties.getProcessor().setStreamEnabled(false);
        LlmClientImpl shortTimeoutClient = new LlmClientImpl(WebClient.builder(), kbProperties);

        // 模拟慢响应
        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finishReason\":\"stop\"}]}")
                .addHeader("Content-Type", "application/json")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));

        ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                () -> shortTimeoutClient.complete("system", "user prompt"));

        assertEquals("LLM", ex.getServiceName());
    }

    @Test
    void complete_optionsOverridesModel() throws Exception {
        String responseBody = """
                {
                    "id": "chatcmpl-456",
                    "object": "chat.completion",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": "response"
                            },
                            "finishReason": "stop"
                        }
                    ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"));

        client.complete("system", "user prompt", Map.of("model", "gpt-3.5-turbo"));

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();

        // 验证请求体中使用了覆盖的模型
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(body, Map.class);
        assertEquals("gpt-3.5-turbo", requestMap.get("model"));
    }

    @Test
    void complete_sendsCorrectSystemAndUserPrompts() throws Exception {
        String responseBody = """
                {
                    "id": "chatcmpl-789",
                    "object": "chat.completion",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": "ok"
                            },
                            "finishReason": "stop"
                        }
                    ]
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"));

        client.complete("你是知识助手", "请解释微服务架构");

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();

        // 验证请求体中包含正确的 messages
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(body, Map.class);
        @SuppressWarnings("unchecked")
        var messages = (java.util.List<Map<String, String>>) requestMap.get("messages");

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("你是知识助手", messages.get(0).get("content"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("请解释微服务架构", messages.get(1).get("content"));
    }

    @Test
    void complete_sendsAuthorizationHeader() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finishReason\":\"stop\"}]}")
                .addHeader("Content-Type", "application/json"));

        client.complete("system", "user");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("Bearer test-llm-key", recorded.getHeader("Authorization"));
    }

    @Test
    void complete_postsToCorrectPath() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finishReason\":\"stop\"}]}")
                .addHeader("Content-Type", "application/json"));

        client.complete("system", "user");

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("/v1/chat/completions", recorded.getPath());
        assertEquals("POST", recorded.getMethod());
    }

    @Test
    void complete_usesDefaultModelWhenNoOptionsProvided() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finishReason\":\"stop\"}]}")
                .addHeader("Content-Type", "application/json"));

        client.complete("system", "user");

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();

        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(body, Map.class);
        assertEquals("gpt-4o", requestMap.get("model"));
    }

    @Test
    void complete_emptyChoices_throwsExternalServiceException() {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"id\":\"chatcmpl-err\",\"object\":\"chat.completion\",\"choices\":[]}")
                .addHeader("Content-Type", "application/json"));

        ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                () -> client.complete("system", "user"));

        assertEquals("LLM", ex.getServiceName());
    }

    @Test
    void complete_optionsPassesExtraParameters() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finishReason\":\"stop\"}]}")
                .addHeader("Content-Type", "application/json"));

        client.complete("system", "user", Map.of("temperature", 0.7, "max_tokens", 1000));

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();

        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(body, Map.class);
        assertEquals(0.7, ((Number) requestMap.get("temperature")).doubleValue(), 0.001);
        assertEquals(1000, ((Number) requestMap.get("max_tokens")).intValue());
    }

    // ── 流式响应（SSE）相关测试 ──

    /**
     * 构造 OpenAI 兼容的 SSE 响应体。每个 chunk 用 "data: {json}\n\n" 分隔，
     * 最终以 "data: [DONE]\n\n" 结束。
     */
    private static String sseBody(String... contentChunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : contentChunks) {
            sb.append("data: ")
                    .append("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"")
                    .append(chunk.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\"}}]}")
                    .append("\n\n");
        }
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    @Test
    void complete_streaming_concatenatesDeltaContent() throws Exception {
        kbProperties.getProcessor().setStreamEnabled(true);
        LlmClientImpl streamingClient = new LlmClientImpl(WebClient.builder(), kbProperties);

        mockWebServer.enqueue(new MockResponse()
                .setBody(sseBody("Hello", " ", "Streaming", "!"))
                .addHeader("Content-Type", "text/event-stream"));

        String result = streamingClient.complete("你是一个助手", "say something");

        assertEquals("Hello Streaming!", result);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(body, Map.class);
        assertEquals(Boolean.TRUE, requestMap.get("stream"));
    }

    @Test
    void complete_streaming_optionsCanForceNonStream() throws Exception {
        kbProperties.getProcessor().setStreamEnabled(true);
        LlmClientImpl streamingClient = new LlmClientImpl(WebClient.builder(), kbProperties);

        mockWebServer.enqueue(new MockResponse()
                .setBody(
                        "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"non-stream\"},\"finishReason\":\"stop\"}]}")
                .addHeader("Content-Type", "application/json"));

        String result = streamingClient.complete("system", "user", Map.of("stream", false));

        assertEquals("non-stream", result);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(body, Map.class);
        // stream 字段不应出现在请求体中
        assertEquals(null, requestMap.get("stream"));
    }

    @Test
    void complete_nonStreaming_optionsCanForceStream() throws Exception {
        // 全局非流式（默认 setUp），单次通过 options 强制流式
        mockWebServer.enqueue(new MockResponse()
                .setBody(sseBody("ok"))
                .addHeader("Content-Type", "text/event-stream"));

        String result = client.complete("system", "user", Map.of("stream", true));

        assertEquals("ok", result);

        RecordedRequest recorded = mockWebServer.takeRequest();
        String body = recorded.getBody().readUtf8();
        @SuppressWarnings("unchecked")
        Map<String, Object> requestMap = objectMapper.readValue(body, Map.class);
        assertEquals(Boolean.TRUE, requestMap.get("stream"));
    }

    @Test
    void complete_streaming_idleTimeout_throwsExternalServiceException() {
        kbProperties.getProcessor().setStreamEnabled(true);
        kbProperties.getProcessor().setLlmStreamIdleTimeoutMs(200);
        kbProperties.getProcessor().setLlmTimeoutMs(5000);
        LlmClientImpl streamingClient = new LlmClientImpl(WebClient.builder(), kbProperties);

        // 服务端建立连接后迟迟不发 chunk，触发 chunk 间空闲超时
        mockWebServer.enqueue(new MockResponse()
                .setBody(sseBody("late"))
                .addHeader("Content-Type", "text/event-stream")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));

        ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                () -> streamingClient.complete("system", "user"));

        assertEquals("LLM", ex.getServiceName());
    }

    @Test
    void complete_streaming_emptyResponse_throwsExternalServiceException() {
        kbProperties.getProcessor().setStreamEnabled(true);
        LlmClientImpl streamingClient = new LlmClientImpl(WebClient.builder(), kbProperties);

        // 直接发 [DONE]，没有任何内容 chunk
        mockWebServer.enqueue(new MockResponse()
                .setBody("data: [DONE]\n\n")
                .addHeader("Content-Type", "text/event-stream"));

        ExternalServiceException ex = assertThrows(ExternalServiceException.class,
                () -> streamingClient.complete("system", "user"));

        assertEquals("LLM", ex.getServiceName());
    }
}
