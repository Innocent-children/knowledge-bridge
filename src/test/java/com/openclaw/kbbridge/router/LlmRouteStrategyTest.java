package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.client.LlmClient;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.QueryFlags;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LlmRouteStrategy 单元测试。
 */
class LlmRouteStrategyTest {

    private LlmClient llmClient;
    private LlmRouteStrategy strategy;
    private RuleBasedRouteStrategy fallbackStrategy;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);

        KbProperties.Query queryConfig = new KbProperties.Query();
        queryConfig.setScoreThreshold(0.6);
        queryConfig.setDefaultRoute("KB_PLUS_LLM");
        queryConfig.setRules(List.of());

        fallbackStrategy = new RuleBasedRouteStrategy(queryConfig);
        strategy = new LlmRouteStrategy(llmClient, queryConfig, fallbackStrategy,
                new tools.jackson.databind.ObjectMapper());
    }

    private QueryRequest request(String question, QueryFlags flags) {
        return new QueryRequest("req-1", "user-1", null, null, null, question, null, false, flags);
    }

    @Nested
    @DisplayName("#kb 标签和 Flags 优先级测试")
    class PriorityTests {

        @Test
        @DisplayName("问题包含 #kb → KB_ONLY，不调用 LLM")
        void kbTag_returnsKbOnly() {
            QueryRequest req = request("#kb 任何问题", null);
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(req));
            verifyNoInteractions(llmClient);
        }

        @Test
        @DisplayName("strictKbOnly=true → KB_ONLY，不调用 LLM")
        void strictKbOnly_returnsKbOnly() {
            QueryRequest req = request("任何问题", new QueryFlags(true, false));
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(req));
            verifyNoInteractions(llmClient);
        }

        @Test
        @DisplayName("#kb 优先于 strictKbOnly（两者都指向 KB_ONLY）")
        void kbTag_withStrictKbOnly() {
            QueryRequest req = request("#kb 任何问题", new QueryFlags(true, false));
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(req));
            verifyNoInteractions(llmClient);
        }

        @Test
        @DisplayName("flags 为 null 时调用 LLM")
        void nullFlags_callsLlm() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("{\"route\": \"KB_PLUS_LLM\", \"confidence\": 0.9}");
            QueryRequest req = request("问题", null);
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(req));
            verify(llmClient).complete(anyString(), eq("问题"), anyMap());
        }
    }

    @Nested
    @DisplayName("LLM 分类测试")
    class LlmClassificationTests {

        @Test
        @DisplayName("LLM 返回高置信度 KB_PLUS_LLM")
        void highConfidence_returnsLlmRoute() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("{\"route\": \"KB_PLUS_LLM\", \"confidence\": 0.85}");
            QueryRequest req = request("如何部署服务？", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(req));
        }

        @Test
        @DisplayName("LLM 返回 LLM_ONLY 路由")
        void llmOnly_route() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("{\"route\": \"LLM_ONLY\", \"confidence\": 0.95}");
            QueryRequest req = request("今天天气怎么样？", new QueryFlags(false, false));
            assertEquals(QueryRoute.LLM_ONLY, strategy.resolve(req));
        }

        @Test
        @DisplayName("LLM 返回 KB_ONLY 路由")
        void kbOnly_route() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("{\"route\": \"KB_ONLY\", \"confidence\": 0.8}");
            QueryRequest req = request("公司报销流程是什么？", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(req));
        }
    }

    @Nested
    @DisplayName("低置信度回退测试")
    class LowConfidenceFallbackTests {

        @Test
        @DisplayName("置信度低于阈值时回退到规则策略")
        void lowConfidence_fallsBackToRule() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("{\"route\": \"KB_PLUS_LLM\", \"confidence\": 0.3}");
            // 问题不匹配任何规则，规则策略返回默认路由 KB_PLUS_LLM
            QueryRequest req = request("普通问题", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(req));
        }

        @Test
        @DisplayName("置信度恰好等于阈值时使用 LLM 结果")
        void exactThreshold_usesLlmResult() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("{\"route\": \"KB_ONLY\", \"confidence\": 0.6}");
            QueryRequest req = request("问题", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(req));
        }

        @Test
        @DisplayName("置信度略低于阈值时回退到规则策略，#kb 触发 KB_ONLY")
        void justBelowThreshold_fallsBack_kbTag() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("{\"route\": \"LLM_ONLY\", \"confidence\": 0.59}");
            // #kb 在问题中，但 LLM 策略先检查 #kb 标签，直接返回 KB_ONLY
            QueryRequest req = request("#kb 问题", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_ONLY, strategy.resolve(req));
        }
    }

    @Nested
    @DisplayName("LLM 失败回退测试")
    class LlmFailureFallbackTests {

        @Test
        @DisplayName("LLM 调用抛出异常时回退到规则策略")
        void llmException_fallsBackToRule() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenThrow(new ExternalServiceException("LLM error", null, "LLM", 500));
            QueryRequest req = request("普通问题", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(req));
        }

        @Test
        @DisplayName("LLM 返回空响应时回退到规则策略")
        void emptyResponse_fallsBackToRule() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn("");
            QueryRequest req = request("普通问题", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(req));
        }

        @Test
        @DisplayName("LLM 返回 null 时回退到规则策略")
        void nullResponse_fallsBackToRule() {
            when(llmClient.complete(anyString(), anyString(), anyMap()))
                    .thenReturn(null);
            QueryRequest req = request("普通问题", new QueryFlags(false, false));
            assertEquals(QueryRoute.KB_PLUS_LLM, strategy.resolve(req));
        }
    }

    @Nested
    @DisplayName("JSON 解析测试")
    class JsonParsingTests {

        @Test
        @DisplayName("解析标准 JSON 响应")
        void standardJson() {
            LlmRouteStrategy.LlmRouteResult result = strategy
                    .parseLlmResponse("{\"route\": \"KB_PLUS_LLM\", \"confidence\": 0.85}");
            assertNotNull(result);
            assertEquals(QueryRoute.KB_PLUS_LLM, result.route());
            assertEquals(0.85, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("解析包含额外文本的 JSON 响应")
        void jsonWithSurroundingText() {
            LlmRouteStrategy.LlmRouteResult result = strategy
                    .parseLlmResponse("根据分析，结果如下：{\"route\": \"LLM_ONLY\", \"confidence\": 0.9} 以上是分类结果。");
            assertNotNull(result);
            assertEquals(QueryRoute.LLM_ONLY, result.route());
            assertEquals(0.9, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("解析无效 JSON 返回 null")
        void invalidJson_returnsNull() {
            assertNull(strategy.parseLlmResponse("这不是JSON"));
        }

        @Test
        @DisplayName("解析未知路由值返回 null")
        void unknownRoute_returnsNull() {
            assertNull(strategy.parseLlmResponse("{\"route\": \"UNKNOWN_ROUTE\", \"confidence\": 0.9}"));
        }

        @Test
        @DisplayName("解析缺少 route 字段返回 null")
        void missingRoute_returnsNull() {
            assertNull(strategy.parseLlmResponse("{\"confidence\": 0.9}"));
        }

        @Test
        @DisplayName("解析缺少 confidence 字段默认为 0.0")
        void missingConfidence_defaultsToZero() {
            LlmRouteStrategy.LlmRouteResult result = strategy.parseLlmResponse("{\"route\": \"KB_ONLY\"}");
            assertNotNull(result);
            assertEquals(QueryRoute.KB_ONLY, result.route());
            assertEquals(0.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("空字符串返回 null")
        void emptyString_returnsNull() {
            assertNull(strategy.parseLlmResponse(""));
        }

        @Test
        @DisplayName("null 返回 null")
        void nullInput_returnsNull() {
            assertNull(strategy.parseLlmResponse(null));
        }
    }
}
