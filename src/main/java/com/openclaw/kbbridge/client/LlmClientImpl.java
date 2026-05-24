package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LlmClient 的 OpenAI 兼容实现。
 * <p>
 * 特性：
 * <ul>
 * <li>支持 OpenAI 兼容的 chat completions 接口（普通响应和流式 SSE 响应）</li>
 * <li>默认走流式响应，避免长 LLM 请求因 TCP 长空闲被中间链路静默丢弃；
 * 可通过 {@code kb.processor.stream-enabled=false} 全局关闭，或 options 中传 {@code stream=false} 单次关闭</li>
 * <li>响应超时由 {@code kb.processor.llm-timeout-ms} 控制（默认 30s）</li>
 * <li>流式 chunk 之间的空闲超时由 {@code kb.processor.llm-stream-idle-timeout-ms} 控制（默认 120s）</li>
 * <li>失败不重试（LLM 调用非幂等）</li>
 * <li>失败转换为 ExternalServiceException（serviceName="LLM"）</li>
 * </ul>
 * </p>
 */
@Component
public class LlmClientImpl implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClientImpl.class);

    private static final String SERVICE_NAME = "LLM";

    /** OpenAI 流式响应的终止标记。 */
    private static final String SSE_DONE_MARKER = "[DONE]";

    private final WebClient webClient;
    private final KbProperties.Processor processorConfig;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数，通过 WebClient.Builder 和 KbProperties 初始化 WebClient。
     *
     * @param webClientBuilder Spring 注入的预配置 WebClient.Builder
     * @param kbProperties     统一配置
     */
    public LlmClientImpl(WebClient.Builder webClientBuilder, KbProperties kbProperties) {
        this.processorConfig = kbProperties.getProcessor();
        this.webClient = webClientBuilder
                .baseUrl(processorConfig.getLlmBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + processorConfig.getLlmApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
        // LLM 流式响应解析仅做局部 JSON 反序列化，不依赖 Spring 全局配置，独立 ObjectMapper 即可
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用 LLM 完成对话，使用默认模型和参数。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 生成的文本内容
     * @throws ExternalServiceException 当 LLM 调用失败时抛出
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, Map.of());
    }

    /**
     * 调用 LLM 完成对话，支持额外参数覆盖。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param options      额外参数，可覆盖 model、stream 等默认配置
     * @return LLM 生成的文本内容
     * @throws ExternalServiceException 当 LLM 调用失败时抛出
     */
    @Override
    public String complete(String systemPrompt, String userPrompt, Map<String, Object> options) {
        boolean stream = resolveStream(options);
        String model = resolveModel(options);
        log.info("LLM 请求: model={}, stream={}, systemPrompt={}, userPrompt={}",
                model, stream, systemPrompt, userPrompt);

        long startTime = System.currentTimeMillis();
        Map<String, Object> requestBody = buildRequestBody(systemPrompt, userPrompt, options, stream);

        try {
            String content = stream
                    ? completeStreaming(requestBody, startTime, model)
                    : completeBlocking(requestBody, startTime, model);
            long latencyMs = System.currentTimeMillis() - startTime;
            log.info("LLM 响应: model={}, stream={}, latencyMs={}, content={}",
                    model, stream, latencyMs, content);
            return content;

        } catch (WebClientResponseException ex) {
            log.error("LLM complete HTTP 错误 ({}): {}", ex.getStatusCode().value(), ex.getMessage());
            throw new ExternalServiceException(
                    "LLM chat completions HTTP error: " + ex.getStatusCode(),
                    null, SERVICE_NAME, ex.getStatusCode().value(), ex);

        } catch (WebClientRequestException ex) {
            log.error("LLM complete 连接失败: {}", ex.getMessage());
            throw new ExternalServiceException(
                    "LLM chat completions 连接失败: " + ex.getMessage(),
                    null, SERVICE_NAME, null, ex);

        } catch (ExternalServiceException ex) {
            throw ex;

        } catch (Exception ex) {
            log.error("LLM complete 调用异常: {}", ex.getMessage());
            throw new ExternalServiceException(
                    "LLM chat completions 调用异常: " + ex.getMessage(),
                    null, SERVICE_NAME, null, ex);
        }
    }

    /**
     * 非流式调用：请求体不带 {@code stream} 字段，按完整 JSON 响应解析。
     */
    private String completeBlocking(Map<String, Object> requestBody, long startTime, String model) {
        Duration timeout = Duration.ofMillis(processorConfig.getLlmTimeoutMs());
        ChatCompletionResponse response = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new WebClientResponseException(
                                clientResponse.statusCode().value(),
                                "LLM chat completions error: " + body,
                                null, null, null)))
                .bodyToMono(ChatCompletionResponse.class)
                .block(timeout);
        return extractContent(response);
    }

    /**
     * 流式调用：请求体带 {@code stream:true}，按 OpenAI 兼容的 SSE 协议拼接 delta.content。
     * <p>
     * 长请求场景下 SSE 让 TCP 链路始终有数据流动，避免被中间盒子静默丢弃。
     * 同时使用 chunk 间空闲超时（llmStreamIdleTimeoutMs）能更快发现死连接。
     * </p>
     */
    private String completeStreaming(Map<String, Object> requestBody, long startTime, String model) {
        Duration totalTimeout = Duration.ofMillis(processorConfig.getLlmTimeoutMs());
        Duration idleTimeout = Duration.ofMillis(processorConfig.getLlmStreamIdleTimeoutMs());

        ParameterizedTypeReference<ServerSentEvent<String>> sseType =
                new ParameterizedTypeReference<>() {};

        Flux<ServerSentEvent<String>> events = webClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new WebClientResponseException(
                                clientResponse.statusCode().value(),
                                "LLM chat completions error: " + body,
                                null, null, null)))
                .bodyToFlux(sseType);

        StringBuilder content = new StringBuilder();
        long chunkCount = events
                .timeout(idleTimeout) // chunk 间空闲超时：超过即认为连接已死
                .takeUntil(event -> isDoneEvent(event))
                .doOnNext(event -> {
                    String delta = extractStreamingDelta(event);
                    if (delta != null && !delta.isEmpty()) {
                        content.append(delta);
                    }
                })
                .count()
                .block(totalTimeout); // 总超时：覆盖整次请求的最大时长

        if (content.isEmpty()) {
            throw new ExternalServiceException(
                    "LLM 流式响应未返回任何内容（chunkCount=" + chunkCount + "）",
                    null, SERVICE_NAME, null);
        }
        return content.toString();
    }

    private boolean isDoneEvent(ServerSentEvent<String> event) {
        String data = event != null ? event.data() : null;
        return data != null && SSE_DONE_MARKER.equals(data.trim());
    }

    /**
     * 从 SSE 事件中提取 {@code choices[0].delta.content}，兼容部分网关把 finishReason 单独发出来的情况。
     */
    private String extractStreamingDelta(ServerSentEvent<String> event) {
        if (event == null) {
            return null;
        }
        String data = event.data();
        if (data == null || data.isEmpty()) {
            return null;
        }
        String trimmed = data.trim();
        if (SSE_DONE_MARKER.equals(trimmed)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            JsonNode choices = node.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode delta = choices.get(0).get("delta");
            if (delta == null) {
                // 部分实现把最后一条事件用 message 字段返回，兼容一下
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    JsonNode msgContent = message.get("content");
                    return msgContent != null && !msgContent.isNull() ? msgContent.asText() : null;
                }
                return null;
            }
            JsonNode contentNode = delta.get("content");
            return contentNode != null && !contentNode.isNull() ? contentNode.asText() : null;
        } catch (Exception ex) {
            log.warn("解析 LLM SSE chunk 失败，已忽略: data={}, error={}", trimmed, ex.getMessage());
            return null;
        }
    }

    /**
     * 构建 OpenAI 兼容的请求体。
     *
     * @param stream 是否启用流式响应
     */
    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt,
            Map<String, Object> options, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", resolveModel(options));

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);

        if (stream) {
            body.put("stream", true);
        }

        // 将 options 中除 model/stream 以外的参数合并到请求体（stream 已由全局或 options 决定）
        if (options != null) {
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                String key = entry.getKey();
                if ("model".equals(key) || "stream".equals(key)) {
                    continue;
                }
                body.put(key, entry.getValue());
            }
        }

        return body;
    }

    /**
     * 从 options 中获取 model，若未指定则使用配置的默认模型。
     */
    private String resolveModel(Map<String, Object> options) {
        if (options != null && options.containsKey("model")) {
            return options.get("model").toString();
        }
        return processorConfig.getLlmModel();
    }

    /**
     * 单次调用是否使用流式：options 显式指定优先，否则取全局配置。
     */
    private boolean resolveStream(Map<String, Object> options) {
        if (options != null && options.containsKey("stream")) {
            Object value = options.get("stream");
            if (value instanceof Boolean b) {
                return b;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return processorConfig.isStreamEnabled();
    }

    /**
     * 从 OpenAI 兼容响应中提取 choices[0].message.content（非流式分支使用）。
     */
    private String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new ExternalServiceException(
                    "LLM 响应为空或不包含 choices",
                    null, SERVICE_NAME, null);
        }
        ChatCompletionResponse.Choice choice = response.choices().getFirst();
        if (choice.message() == null || choice.message().content() == null) {
            throw new ExternalServiceException(
                    "LLM 响应 choice 中不包含 message.content",
                    null, SERVICE_NAME, null);
        }
        return choice.message().content();
    }

    // ── OpenAI 兼容响应 DTO（内部使用） ──

    /**
     * OpenAI chat completions 响应结构（非流式）。
     */
    private record ChatCompletionResponse(
            String id,
            String object,
            List<Choice> choices) {
        record Choice(
                int index,
                Message message,
                String finishReason) {
        }

        record Message(
                String role,
                String content) {
        }
    }
}
