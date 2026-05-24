package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.QueryFlags;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RuleBasedRouteStrategy 单元测试。
 * 覆盖 #kb 标签优先级、strictKbOnly 标志、规则匹配、默认路由等场景。
 */
class RuleBasedRouteStrategyTest {

    private RuleBasedRouteStrategy strategy;

    @BeforeEach
    void setUp() {
        KbProperties.Query queryConfig = new KbProperties.Query();
        queryConfig.setDefaultRoute("KB_PLUS_LLM");

        // 配置规则：chitchat category with keywords → LLM_ONLY
        KbProperties.Rule chitchatRule = new KbProperties.Rule();
        chitchatRule.setCategory("chitchat");
        chitchatRule.setKeywords(List.of("你好", "hello"));
        chitchatRule.setRoute("LLM_ONLY");

        queryConfig.setRules(List.of(chitchatRule));

        strategy = new RuleBasedRouteStrategy(queryConfig);
    }

    private QueryRequest request(String question, QueryFlags flags) {
        return new QueryRequest("req-1", "user-1", null, null, null, question, null, false, flags);
    }

    @Nested
    @DisplayName("#kb 标签优先级测试")
    class KbTagPriorityTests {

        @Test
        @DisplayName("问题包含 #kb → KB_ONLY，无论其他规则")
        void kbTag_returnsKbOnly() {
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(request("#kb 如何部署", null)));
        }

        @Test
        @DisplayName("#kb 优先级高于 strictKbOnly 标志（两者都指向 KB_ONLY）")
        void kbTag_withStrictKbOnly() {
            QueryFlags flags = new QueryFlags(true, false);
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(request("#kb 查一下", flags)));
        }

        @Test
        @DisplayName("#kb 优先级高于 chitchat 规则")
        void kbTag_overridesChitchatRule() {
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(request("#kb 你好", null)));
        }
    }

    @Nested
    @DisplayName("strictKbOnly 标志测试")
    class StrictKbOnlyTests {

        @Test
        @DisplayName("strictKbOnly=true → KB_ONLY")
        void strictKbOnly_returnsKbOnly() {
            QueryFlags flags = new QueryFlags(true, false);
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(request("随便什么问题", flags)));
        }

        @Test
        @DisplayName("strictKbOnly=true 优先于 chitchat 规则")
        void strictKbOnly_overridesChitchatRule() {
            QueryFlags flags = new QueryFlags(true, false);
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(request("你好", flags)));
        }
    }

    @Nested
    @DisplayName("优先级链测试")
    class PriorityChainTests {

        @Test
        @DisplayName("完整优先级: #kb > strictKbOnly > rules > default")
        void fullPriorityChain() {
            // #kb 最高
            assertEquals(QueryRoute.KB_ONLY,
                    strategy.resolve(request("#kb 你好", new QueryFlags(true, false))));
            // strictKbOnly 次之
            assertEquals(QueryRoute.KB_ONLY,
                    strategy.resolve(request("普通问题", new QueryFlags(true, false))));
            // 规则匹配（chitchat）
            assertEquals(QueryRoute.LLM_ONLY,
                    strategy.resolve(request("你好，请问", new QueryFlags(false, false))));
            // 默认路由
            assertEquals(QueryRoute.KB_PLUS_LLM,
                    strategy.resolve(request("普通问题", new QueryFlags(false, false))));
        }
    }

    @Nested
    @DisplayName("规则匹配测试")
    class RuleMatchingTests {

        @Test
        @DisplayName("问题匹配 category keywords → 对应路由")
        void questionMatchingKeyword_returnsRuleRoute() {
            assertEquals(QueryRoute.LLM_ONLY, strategy.resolve(request("你好，请问", null)));
        }

        @Test
        @DisplayName("问题不匹配任何规则 → 默认路由 KB_PLUS_LLM")
        void questionWithoutMatch_returnsDefault() {
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(request("今天天气怎么样", null)));
        }
    }

    @Nested
    @DisplayName("null flags 处理测试")
    class NullFlagsTests {

        @Test
        @DisplayName("flags 为 null 且问题包含 #kb → KB_ONLY")
        void nullFlags_withKbTag() {
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(request("#kb 查询", null)));
        }

        @Test
        @DisplayName("flags 为 null 且无规则匹配 → 默认路由")
        void nullFlags_noMatch_returnsDefault() {
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(request("普通问题", null)));
        }
    }
}
