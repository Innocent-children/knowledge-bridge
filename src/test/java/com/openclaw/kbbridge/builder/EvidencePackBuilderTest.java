package com.openclaw.kbbridge.builder;

import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EvidencePackBuilder 单元测试。
 * 覆盖排序、截断、字段正确性、路由指令等核心逻辑。
 */
class EvidencePackBuilderTest {

    private KbProperties kbProperties;
    private KbMetrics kbMetrics;
    private EvidencePackBuilder builder;

    @BeforeEach
    void setUp() {
        kbProperties = new KbProperties();
        kbMetrics = new KbMetrics(new SimpleMeterRegistry());
        // 使用默认配置: maxSources=5, maxContentLength=2000, maxTotalLength=8000
        builder = new EvidencePackBuilder(kbProperties, kbMetrics);
    }

    // ── 排序测试 ──

    @Test
    void build_shouldSortSourcesByScoreDescending() {
        List<EvidenceSource> sources = List.of(
                source("low", 0.3),
                source("high", 0.95),
                source("mid", 0.7));

        QueryResponse resp = builder.build("req-1", QueryRoute.KB_ONLY, sources);

        assertEquals(3, resp.sources().size());
        assertEquals(0.95, resp.sources().get(0).score());
        assertEquals(0.7, resp.sources().get(1).score());
        assertEquals(0.3, resp.sources().get(2).score());
    }

    // ── maxSources 截断测试 ──

    @Test
    void build_shouldTruncateToMaxSources_keepingHighestScores() {
        List<EvidenceSource> sources = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sources.add(source("doc-" + i, 0.1 * (i + 1)));
        }

        QueryResponse resp = builder.build("req-2", QueryRoute.KB_ONLY, sources);

        assertEquals(5, resp.sources().size());
        // 应保留 score 最高的 5 条: 0.8, 0.7, 0.6, 0.5, 0.4
        assertEquals(0.8, resp.sources().get(0).score(), 0.001);
        assertEquals(0.4, resp.sources().get(4).score(), 0.001);
        assertTrue(resp.retrievalQuality().truncated());
        assertEquals(8, resp.retrievalQuality().originalHitCount());
    }

    @Test
    void build_shouldNotTruncate_whenSourcesWithinMaxSources() {
        List<EvidenceSource> sources = List.of(
                source("a", 0.9),
                source("b", 0.8));

        QueryResponse resp = builder.build("req-3", QueryRoute.KB_ONLY, sources);

        assertEquals(2, resp.sources().size());
        assertFalse(resp.retrievalQuality().truncated());
        assertEquals(2, resp.retrievalQuality().originalHitCount());
    }

    // ── maxContentLength 单条截断测试 ──

    @Test
    void build_shouldTruncateContentAndAppendMarker_whenExceedsMaxContentLength() {
        // 设置较小的 maxContentLength 便于测试
        kbProperties.getQuery().setMaxContentLength(10);
        builder = new EvidencePackBuilder(kbProperties, kbMetrics);

        List<EvidenceSource> sources = List.of(
                new EvidenceSource("ds", "title", "abcdefghijklmnop", 0.9, Map.of()));

        QueryResponse resp = builder.build("req-4", QueryRoute.KB_ONLY, sources);

        String content = resp.sources().getFirst().content();
        assertEquals("abcdefghij[...]", content);
        assertTrue(resp.retrievalQuality().truncated());
    }

    @Test
    void build_shouldNotTruncateContent_whenWithinMaxContentLength() {
        kbProperties.getQuery().setMaxContentLength(100);
        builder = new EvidencePackBuilder(kbProperties, kbMetrics);

        List<EvidenceSource> sources = List.of(
                new EvidenceSource("ds", "title", "short content", 0.9, Map.of()));

        QueryResponse resp = builder.build("req-5", QueryRoute.KB_ONLY, sources);

        assertEquals("short content", resp.sources().getFirst().content());
    }

    // ── maxTotalLength 总长度截断测试 ──

    @Test
    void build_shouldRemoveLowestScoreSources_whenTotalExceedsMaxTotalLength() {
        kbProperties.getQuery().setMaxTotalLength(30);
        kbProperties.getQuery().setMaxSources(10);
        builder = new EvidencePackBuilder(kbProperties, kbMetrics);

        // 每条 content 15 字符，3 条共 45 > 30
        List<EvidenceSource> sources = List.of(
                new EvidenceSource("ds", "t1", "a".repeat(15), 0.9, Map.of()),
                new EvidenceSource("ds", "t2", "b".repeat(15), 0.7, Map.of()),
                new EvidenceSource("ds", "t3", "c".repeat(15), 0.5, Map.of()));

        QueryResponse resp = builder.build("req-6", QueryRoute.KB_ONLY, sources);

        // 总长度 30，最多放 2 条（每条 15）
        assertEquals(2, resp.sources().size());
        assertEquals(0.9, resp.sources().get(0).score());
        assertEquals(0.7, resp.sources().get(1).score());
        assertTrue(resp.retrievalQuality().truncated());
        assertEquals(3, resp.retrievalQuality().originalHitCount());
    }

    // ── truncated 标志和 originalHitCount 测试 ──

    @Test
    void build_shouldSetTruncatedFalse_whenNoTruncationOccurs() {
        List<EvidenceSource> sources = List.of(source("a", 0.9));

        QueryResponse resp = builder.build("req-7", QueryRoute.KB_ONLY, sources);

        assertFalse(resp.retrievalQuality().truncated());
        assertEquals(1, resp.retrievalQuality().originalHitCount());
        assertEquals(1, resp.retrievalQuality().hitCount());
    }

    // ── allowModelSupplement 测试 ──

    @Test
    void build_shouldSetAllowModelSupplementFalse_forKbOnly() {
        QueryResponse resp = builder.build("req-8", QueryRoute.KB_ONLY, List.of(source("a", 0.9)));
        assertFalse(resp.allowModelSupplement());
    }

    @Test
    void build_shouldSetAllowModelSupplementTrue_forKbPlusLlm() {
        QueryResponse resp = builder.build("req-10", QueryRoute.KB_PLUS_LLM, List.of(source("a", 0.9)));
        assertTrue(resp.allowModelSupplement());
    }

    @Test
    void build_shouldSetAllowModelSupplementFalse_forLlmOnly() {
        QueryResponse resp = builder.build("req-11", QueryRoute.LLM_ONLY, List.of());
        assertFalse(resp.allowModelSupplement());
    }

    // ── instructions 测试 ──

    @Test
    void build_shouldGenerateStrictInstructions_forKbOnly() {
        QueryResponse resp = builder.build("req-12", QueryRoute.KB_ONLY, List.of(source("a", 0.9)));

        assertTrue(resp.instructions().stream().anyMatch(text -> text.startsWith("只依据 sources 回答") && text.contains("不要使用自身知识")));
        assertTrue(resp.instructions().contains("不要编造未在 sources 中出现的事实"));
        assertTrue(resp.instructions().stream().anyMatch(text -> text.contains("如 sources 不足") && text.contains("知识库中没有相关信息")));
        assertTrue(resp.instructions().stream().anyMatch(text -> text.contains("## 参考原文") && text.contains("逐字")));
        assertTrue(resp.instructions().stream().anyMatch(text -> text.contains("不要改写") && text.contains("不要对原文内容做任何评论")));
    }

    @Test
    void build_shouldGenerateSupplementInstructions_forKbPlusLlm() {
        QueryResponse resp = builder.build("req-14", QueryRoute.KB_PLUS_LLM, List.of(source("a", 0.9)));

        assertTrue(resp.instructions().contains("优先依据 sources 回答"));
        assertTrue(resp.instructions().contains("不要编造未在 sources 中出现的事实"));
        assertTrue(resp.instructions().stream().anyMatch(text -> text.contains("可补充通用说明") && text.contains("明确标注") && text.contains("补充信息")));
        assertTrue(resp.instructions().stream().anyMatch(text -> text.contains("## 参考原文") && text.contains("逐字")));
        assertTrue(resp.instructions().stream().anyMatch(text -> text.contains("完全一致") && text.contains("不得改写或删减")));
    }

    @Test
    void build_shouldGenerateEmptyInstructions_forLlmOnly() {
        QueryResponse resp = builder.build("req-15", QueryRoute.LLM_ONLY, List.of());

        assertTrue(resp.instructions().isEmpty());
    }

    // ── 空 sources 测试 ──

    @Test
    void build_shouldHandleEmptySources() {
        QueryResponse resp = builder.build("req-16", QueryRoute.KB_ONLY, Collections.emptyList());

        assertTrue(resp.sources().isEmpty());
        assertEquals(0, resp.retrievalQuality().hitCount());
        assertEquals(0, resp.retrievalQuality().originalHitCount());
        assertEquals(Confidence.LOW, resp.retrievalQuality().confidence());
        assertFalse(resp.retrievalQuality().truncated());
    }

    // ── Confidence 分类测试 ──

    @Test
    void build_shouldClassifyConfidenceHigh_whenTopScoreAbove08() {
        QueryResponse resp = builder.build("req-17", QueryRoute.KB_ONLY, List.of(source("a", 0.85)));
        assertEquals(Confidence.HIGH, resp.retrievalQuality().confidence());
    }

    @Test
    void build_shouldClassifyConfidenceMedium_whenTopScoreBetween06And08() {
        QueryResponse resp = builder.build("req-18", QueryRoute.KB_ONLY, List.of(source("a", 0.7)));
        assertEquals(Confidence.MEDIUM, resp.retrievalQuality().confidence());
    }

    @Test
    void build_shouldClassifyConfidenceLow_whenTopScoreBelow06() {
        QueryResponse resp = builder.build("req-19", QueryRoute.KB_ONLY, List.of(source("a", 0.4)));
        assertEquals(Confidence.LOW, resp.retrievalQuality().confidence());
    }

    // ── requestId 和 route 透传测试 ──

    @Test
    void build_shouldPreserveRequestIdAndRoute() {
        QueryResponse resp = builder.build("my-req-id", QueryRoute.KB_PLUS_LLM, List.of(source("a", 0.9)));

        assertEquals("my-req-id", resp.requestId());
        assertEquals(QueryRoute.KB_PLUS_LLM, resp.route());
    }

    // ── null content 处理测试 ──

    @Test
    void build_shouldHandleNullContent() {
        List<EvidenceSource> sources = List.of(
                new EvidenceSource("ds", "title", null, 0.9, Map.of()));

        QueryResponse resp = builder.build("req-20", QueryRoute.KB_ONLY, sources);

        assertEquals(1, resp.sources().size());
        assertNull(resp.sources().getFirst().content());
    }

    // ── 辅助方法 ──

    private EvidenceSource source(String title, double score) {
        return new EvidenceSource("dataset", title, "some content", score, Map.of());
    }
}
