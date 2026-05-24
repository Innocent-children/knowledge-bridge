package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.dto.chat.ChatRequest;
import com.openclaw.kbbridge.dto.chat.ChatResponse;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.service.QueryService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 聊天控制器。
 * <p>
 * 提供 POST /api/v1/chat 端点，接收用户问题，
 * 调用 QueryService 检索知识证据，再调用 LlmClient 生成回答。
 * LLM API Key 保留在服务端，前端只需发送问题文本。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final QueryService queryService;
    private final LlmClient llmClient;

    public ChatController(QueryService queryService, LlmClient llmClient) {
        this.queryService = queryService;
        this.llmClient = llmClient;
    }

    /**
     * 处理聊天请求。
     * <p>
     * 流程：
     * <ol>
     * <li>构建内部 QueryRequest，调用 QueryService 检索证据包</li>
     * <li>根据证据包内容和路由模式，构建系统提示词并调用 LlmClient</li>
     * <li>返回包含回答、路由和证据来源的 ChatResponse</li>
     * </ol>
     * </p>
     *
     * @param request 聊天请求（question 必填）
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        log.info("收到聊天请求, requestId={}, question={}", requestId, request.question());

        // 1. 调用 QueryService 检索证据包
        long retrievalStart = System.currentTimeMillis();
        QueryResponse queryResponse;
        try {
            QueryRequest queryRequest = new QueryRequest(
                    requestId,
                    "console",
                    null,
                    null,
                    null,
                    request.question(),
                    "console",
                    false,
                    null);
            queryResponse = queryService.query(queryRequest);
        } catch (ExternalServiceException ex) {
            log.error("QueryService 外部服务调用失败, requestId={}: {}", requestId, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ChatResponse(null, null, List.of(), false,
                            "Knowledge retrieval failed: " + ex.getMessage()));
        } catch (Exception ex) {
            log.error("QueryService 调用异常, requestId={}: {}", requestId, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ChatResponse(null, null, List.of(), false,
                            "Knowledge retrieval failed: " + ex.getMessage()));
        }
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        // 2. 构建提示词并调用 LlmClient
        List<EvidenceSource> sources = queryResponse.sources();
        QueryRoute route = queryResponse.route();
        boolean hasSources = sources != null && !sources.isEmpty();

        log.info("检索完成, requestId={}, route={}, sourceCount={}, 耗时={}s",
                requestId, route, sources != null ? sources.size() : 0,
                String.format("%.1f", retrievalMs / 1000.0));
        if (hasSources && log.isDebugEnabled()) {
            sources.forEach(s -> log.debug("  source: title={}, score={}, len={}",
                    s.title(), s.score(), s.content() != null ? s.content().length() : 0));
        }

        long llmStart = System.currentTimeMillis();
        try {
            String systemPrompt;
            if (hasSources && route != QueryRoute.LLM_ONLY) {
                systemPrompt = buildEvidenceSystemPrompt(route, sources);
            } else {
                systemPrompt = buildDefaultSystemPrompt();
            }

            String answer = llmClient.complete(systemPrompt, request.question());
            long llmMs = System.currentTimeMillis() - llmStart;
            long totalMs = System.currentTimeMillis() - startTime;
            log.info("聊天完成, requestId={}, route={}, sourceCount={}, answerLength={}, 检索={}s, LLM={}s, 总耗时={}s",
                    requestId, route, sources != null ? sources.size() : 0,
                    answer != null ? answer.length() : 0,
                    String.format("%.1f", retrievalMs / 1000.0),
                    String.format("%.1f", llmMs / 1000.0),
                    String.format("%.1f", totalMs / 1000.0));

            return ResponseEntity.ok(new ChatResponse(
                    answer,
                    route,
                    sources != null ? sources : List.of(),
                    false,
                    null));
        } catch (Exception ex) {
            log.error("LlmClient 调用失败, requestId={}: {}", requestId, ex.getMessage(), ex);
            return ResponseEntity.ok(new ChatResponse(
                    null,
                    route,
                    sources != null ? sources : List.of(),
                    true,
                    "LLM service unavailable: " + ex.getMessage()));
        }
    }

    /**
     * 构建包含证据上下文的系统提示词。
     * <p>
     * KB_ONLY 模式：严格基于 sources 回答，禁止使用自身知识补充、纠正或评判。
     * KB_PLUS_LLM 模式：优先基于 sources，允许补充但需标注。
     * <p>
     * 两种模式都强制要求在回答末尾以 "## 参考原文" 区块逐字附上每条 source 的原文，
     * 便于终端用户核对与追溯。
     */
    String buildEvidenceSystemPrompt(QueryRoute route, List<EvidenceSource> sources) {
        String evidenceContext = sources.stream()
                .map(s -> String.format("[%s] %s\n%s", s.title(), s.dataset(), s.content()))
                .collect(Collectors.joining("\n\n"));

        String instruction = (route == QueryRoute.KB_ONLY)
                ? """
                        你是 Knowledge Bridge 知识库助手。请严格根据下方提供的知识来源回答用户问题。

                        规则：
                        1. 只能使用知识来源中的信息回答，引用来源标题。
                        2. 禁止使用你自身的知识补充、纠正或评判知识来源的内容。
                        3. 如果知识来源不足以回答问题，直接说明"知识库中没有找到相关信息"。
                        4. 不要对知识来源的准确性做任何判断。
                        5. 必须在回答末尾以 "## 参考原文" 为标题，按来源顺序逐字附上原文。
                           - 每条以 "### [编号] {title}（相关度 {score*100}%）" 为小标题
                           - 下面用 Markdown 引用块 "> " 逐字呈现 content，保留原有换行与 Markdown 结构
                           - 不要改写、简化、总结，不要做任何评论"""
                : """
                        你是 Knowledge Bridge 知识库助手。请优先根据下方提供的知识来源回答用户问题。

                        规则：
                        1. 优先使用知识来源中的信息，引用来源标题。
                        2. 如果知识来源不足以完整回答，可以用你的知识补充，但需明确标注"以下为补充信息"。
                        3. 不要编造知识来源中不存在的事实。
                        4. 必须在回答末尾以 "## 参考原文" 为标题，按来源顺序逐字附上原文。
                           - 每条以 "### [编号] {title}（相关度 {score*100}%）" 为小标题
                           - 下面用 Markdown 引用块 "> " 逐字呈现 content，保留原有换行与 Markdown 结构
                           - 参考原文区块内容必须与知识来源完全一致，不得改写或删减""";

        return instruction + "\n\n=== 知识来源 ===\n" + evidenceContext + "\n=== 知识来源结束 ===";
    }

    /**
     * 构建无证据上下文的默认系统提示词。
     */
    String buildDefaultSystemPrompt() {
        return """
                You are a knowledgeable assistant for the Knowledge Bridge system. \
                Answer the user's question to the best of your ability. \
                If you are unsure about something, say so clearly.""";
    }
}
