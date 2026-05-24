package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 查询路由器门面组件。
 * <p>
 * 根据 kb.query.route-strategy 配置选择活跃的路由策略，
 * 并将路由判定委托给对应的 {@link QueryRouteStrategy} 实现。
 * <p>
 * 支持 "rule" 策略（{@link RuleBasedRouteStrategy}）和
 * "llm" 策略（{@link LlmRouteStrategy}）。
 */
@Slf4j
@Component
public class QueryRouter {

    private final QueryRouteStrategy activeStrategy;

    /**
     * 构造查询路由器，根据配置选择活跃策略。
     *
     * @param kbProperties 统一配置
     * @param llmClient    LLM 客户端（用于 LlmRouteStrategy）
     * @param objectMapper Spring 管理的 Jackson 3.x ObjectMapper
     */
    public QueryRouter(KbProperties kbProperties, LlmClient llmClient, ObjectMapper objectMapper) {
        KbProperties.Query queryConfig = kbProperties.getQuery();
        String strategyName = queryConfig.getRouteStrategy();

        RuleBasedRouteStrategy ruleStrategy = new RuleBasedRouteStrategy(queryConfig);

        this.activeStrategy = switch (strategyName) {
            case "rule" -> ruleStrategy;
            case "llm" -> new LlmRouteStrategy(llmClient, queryConfig, ruleStrategy, objectMapper);
            default -> {
                log.warn("未知的路由策略 '{}', 回退到 rule 策略", strategyName);
                yield ruleStrategy;
            }
        };

        log.info("查询路由器初始化完成, 活跃策略: {}", strategyName);
    }

    /**
     * 对查询请求执行路由判定。
     *
     * @param request 查询请求
     * @return 路由结果枚举
     */
    public QueryRoute route(QueryRequest request) {
        return activeStrategy.resolve(request);
    }
}
