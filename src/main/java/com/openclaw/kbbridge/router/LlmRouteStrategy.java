package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.QueryFlags;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 LLM 意图分类的查询路由策略实现。
 * <p>
 * 调用 LLM 对用户问题进行意图分类，输出路由结果和置信度。
 * 当置信度低于配置阈值（kb.query.score-threshold）时，回退到 {@link RuleBasedRouteStrategy}。
 * LLM 调用失败时同样回退到规则策略，确保路由链路可用性。
 * <p>
 * 优先级（从高到低）：
 * <ol>
 * <li>问题包含 #kb → KB_ONLY（最高优先级）</li>
 * <li>flags.strictKbOnly == true → KB_ONLY</li>
 * <li>LLM 意图分类 → 对应路由（置信度 >= 阈值）</li>
 * <li>LLM 置信度不足或调用失败 → 回退到 RuleBasedRouteStrategy</li>
 * </ol>
 */
@Slf4j
public class LlmRouteStrategy implements QueryRouteStrategy {

    private static final String KB_TAG = "#kb";

    private static final String SYSTEM_PROMPT = """
            你是一个查询意图分类器。根据用户的问题，判断应该使用哪种查询路由模式。

            可选的路由模式：
            - LLM_ONLY: 问题是闲聊、通用知识或不需要查询知识库的问题
            - KB_ONLY: 问题需要严格依据知识库回答，不允许模型补充
            - KB_PLUS_LLM: 问题需要知识库优先，但允许模型补充相关信息

            请以 JSON 格式回复，包含 route 和 confidence 两个字段。
            confidence 是 0.0 到 1.0 之间的浮点数，表示你对分类结果的置信度。

            示例回复：
            {"route": "KB_PLUS_LLM", "confidence": 0.85}
            """;

    /**
     * 用于从 LLM 响应中提取 JSON 对象的正则
     */
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[^}]*\"route\"[^}]*}");

    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;
    private final double scoreThreshold;
    private final RuleBasedRouteStrategy fallbackStrategy;

    /**
     * 构造基于 LLM 的路由策略。
     *
     * @param llmClient        LLM 客户端
     * @param queryConfig      查询配置，包含 scoreThreshold
     * @param fallbackStrategy 回退策略（规则策略）
     * @param objectMapper     Spring 管理的 Jackson 3.x ObjectMapper
     */
    public LlmRouteStrategy(LlmClient llmClient, KbProperties.Query queryConfig,
            RuleBasedRouteStrategy fallbackStrategy, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.scoreThreshold = queryConfig.getScoreThreshold();
        this.fallbackStrategy = fallbackStrategy;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据查询请求解析路由结果。
     *
     * @param request 查询请求
     * @return 路由结果枚举
     */
    @Override
    public QueryRoute resolve(QueryRequest request) {
        String question = request.question();
        QueryFlags flags = request.flags();

        // 优先级 1：问题包含 #kb → KB_ONLY（最高优先级）
        if (question != null && question.contains(KB_TAG)) {
            log.debug("LLM路由判定: 问题包含 #kb → KB_ONLY, requestId={}", request.requestId());
            return QueryRoute.KB_ONLY;
        }

        // 优先级 2：strictKbOnly 标志
        if (flags != null && flags.strictKbOnly()) {
            log.debug("LLM路由判定: strictKbOnly=true → KB_ONLY, requestId={}", request.requestId());
            return QueryRoute.KB_ONLY;
        }

        // 优先级 3：LLM 意图分类
        try {
            String llmResponse = llmClient.complete(SYSTEM_PROMPT, request.question(),
                    Map.of("temperature", 0.1));

            LlmRouteResult result = parseLlmResponse(llmResponse);

            if (result == null) {
                log.warn("LLM路由判定: 响应解析失败, 回退到规则策略, requestId={}", request.requestId());
                return fallbackStrategy.resolve(request);
            }

            log.debug("LLM路由判定: route={}, confidence={}, requestId={}",
                    result.route(), result.confidence(), request.requestId());

            if (result.confidence() < scoreThreshold) {
                log.info("LLM路由判定: 置信度 {} 低于阈值 {}, 回退到规则策略, requestId={}",
                        result.confidence(), scoreThreshold, request.requestId());
                return fallbackStrategy.resolve(request);
            }

            return result.route();

        } catch (Exception ex) {
            log.warn("LLM路由判定: LLM调用失败, 回退到规则策略, requestId={}, error={}",
                    request.requestId(), ex.getMessage());
            return fallbackStrategy.resolve(request);
        }
    }

    /**
     * 解析 LLM 响应，提取路由和置信度。
     *
     * @param llmResponse LLM 原始响应文本
     * @return 解析结果，解析失败返回 null
     */
    LlmRouteResult parseLlmResponse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return null;
        }

        try {
            return parseJson(llmResponse);
        } catch (Exception ignored) {
        }

        Matcher matcher = JSON_PATTERN.matcher(llmResponse);
        if (matcher.find()) {
            try {
                return parseJson(matcher.group());
            } catch (Exception ex) {
                log.debug("LLM响应JSON片段解析失败: {}", ex.getMessage());
            }
        }

        return null;
    }

    /**
     * 解析 JSON 字符串为路由结果。
     */
    private LlmRouteResult parseJson(String json) throws JacksonException {
        JsonNode node = objectMapper.readTree(json);

        JsonNode routeNode = node.get("route");
        JsonNode confidenceNode = node.get("confidence");

        if (routeNode == null || !routeNode.isString()) {
            return null;
        }

        String routeStr = routeNode.stringValue();
        QueryRoute route;
        try {
            route = QueryRoute.valueOf(routeStr);
        } catch (IllegalArgumentException ex) {
            log.debug("LLM返回未知路由值: {}", routeStr);
            return null;
        }

        double confidence = 0.0;
        if (confidenceNode != null && confidenceNode.isNumber()) {
            confidence = confidenceNode.doubleValue();
        }

        return new LlmRouteResult(route, confidence);
    }

    /**
     * LLM 路由分类结果。
     *
     * @param route      路由结果
     * @param confidence 置信度 (0.0-1.0)
     */
    record LlmRouteResult(QueryRoute route, double confidence) {
    }
}
