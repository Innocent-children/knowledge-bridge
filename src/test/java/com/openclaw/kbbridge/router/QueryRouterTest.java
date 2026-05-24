package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.QueryFlags;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * QueryRouter 单元测试。
 * 验证门面组件正确委托给活跃策略。
 */
class QueryRouterTest {

    private final LlmClient llmClient = mock(LlmClient.class);

    private QueryRouter createRouter(String strategyName) {
        KbProperties props = new KbProperties();
        KbProperties.Query queryConfig = props.getQuery();
        queryConfig.setRouteStrategy(strategyName);
        queryConfig.setDefaultRoute("KB_PLUS_LLM");

        // #kb pattern is now built into RuleBasedRouteStrategy (priority 1 → KB_ONLY),
        // no longer needs to be configured as a rule.
        queryConfig.setRules(List.of());

        return new QueryRouter(props, llmClient, new tools.jackson.databind.ObjectMapper());
    }

    private QueryRequest request(String question, QueryFlags flags) {
        return new QueryRequest("req-1", "user-1", null, null, null, question, null, false, flags);
    }

    @Test
    @DisplayName("rule 策略正确委托路由判定")
    void ruleStrategy_delegatesCorrectly() {
        QueryRouter router = createRouter("rule");
        // #kb is now built-in priority 1 → KB_ONLY
        assertEquals(QueryRoute.KB_ONLY,
                router.route(request("#kb 问题", new QueryFlags(false, false, false))));
        // Normal question falls through to default route KB_PLUS_LLM
        assertEquals(QueryRoute.KB_PLUS_LLM,
                router.route(request("普通问题", new QueryFlags(false, false, false))));
    }

    @Test
    @DisplayName("未知策略名回退到 rule 策略")
    void unknownStrategy_fallsBackToRule() {
        QueryRouter router = createRouter("unknown");
        // #kb is built-in → KB_ONLY even with unknown strategy (falls back to rule)
        assertEquals(QueryRoute.KB_ONLY,
                router.route(request("#kb 问题", new QueryFlags(false, false, false))));
    }
}
