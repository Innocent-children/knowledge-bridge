package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.QueryFlags;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 基于规则的查询路由策略实现。
 * <p>
 * 路由判定优先级（从高到低）：
 * <ol>
 * <li>问题包含 #kb → KB_ONLY（最高优先级，不管其他规则）</li>
 * <li>flags.strictKbOnly == true → KB_ONLY</li>
 * <li>规则匹配：问题包含 pattern 或 category keywords → 对应路由</li>
 * <li>默认路由：kb.query.default-route（默认 KB_PLUS_LLM）</li>
 * </ol>
 */
@Slf4j
public class RuleBasedRouteStrategy implements QueryRouteStrategy {

    private static final String KB_TAG = "#kb";

    private final List<KbProperties.Rule> rules;
    private final QueryRoute defaultRoute;

    /**
     * 构造基于规则的路由策略。
     *
     * @param queryConfig 查询配置，包含规则列表和默认路由
     */
    public RuleBasedRouteStrategy(KbProperties.Query queryConfig) {
        this.rules = queryConfig.getRules();
        this.defaultRoute = QueryRoute.valueOf(queryConfig.getDefaultRoute());
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
            log.debug("路由判定: 问题包含 #kb → KB_ONLY, requestId={}", request.requestId());
            return QueryRoute.KB_ONLY;
        }

        // 优先级 2：strictKbOnly 标志
        if (flags != null && flags.strictKbOnly()) {
            log.debug("路由判定: strictKbOnly=true → KB_ONLY, requestId={}", request.requestId());
            return QueryRoute.KB_ONLY;
        }

        // 优先级 3：规则匹配
        for (KbProperties.Rule rule : rules) {
            if (matchesRule(question, rule)) {
                QueryRoute route = QueryRoute.valueOf(rule.getRoute());
                log.debug("路由判定: 规则匹配 pattern='{}' → {}, requestId={}",
                        rule.getPattern(), route, request.requestId());
                return route;
            }
        }

        // 优先级 4：默认路由
        log.debug("路由判定: 无匹配规则 → 默认路由 {}, requestId={}", defaultRoute, request.requestId());
        return defaultRoute;
    }

    /**
     * 检查问题是否匹配指定规则。
     *
     * @param question 用户问题
     * @param rule     配置规则
     * @return 是否匹配
     */
    private boolean matchesRule(String question, KbProperties.Rule rule) {
        if (question == null) {
            return false;
        }

        // pattern 匹配
        if (rule.getPattern() != null && !rule.getPattern().isEmpty()) {
            if (question.contains(rule.getPattern())) {
                return true;
            }
        }

        // category + keywords 匹配
        if (rule.getCategory() != null && rule.getKeywords() != null && !rule.getKeywords().isEmpty()) {
            for (String keyword : rule.getKeywords()) {
                if (question.contains(keyword)) {
                    return true;
                }
            }
        }

        return false;
    }
}
