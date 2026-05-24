package com.openclaw.kbbridge.builder;

import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.dto.query.RetrievalQuality;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 证据包构建器。
 * <p>
 * 负责对检索结果按 score 降序排序、执行截断策略（maxSources / maxContentLength / maxTotalLength），
 * 并根据路由模式生成包含 sources、instructions、retrievalQuality 的结构化证据包。
 */
@Component
public class EvidencePackBuilder {

    private final KbProperties.Query queryConfig;
    private final KbMetrics kbMetrics;

    public EvidencePackBuilder(KbProperties kbProperties, KbMetrics kbMetrics) {
        this.queryConfig = kbProperties.getQuery();
        this.kbMetrics = kbMetrics;
    }

    /**
     * 构建证据包。
     *
     * @param requestId 请求唯一标识
     * @param route     查询路由模式
     * @param sources   原始检索结果列表
     * @return 结构化的查询响应（证据包）
     */
    public QueryResponse build(String requestId, QueryRoute route, List<EvidenceSource> sources) {
        if (sources == null || sources.isEmpty()) {
            RetrievalQuality quality = new RetrievalQuality(0, Confidence.LOW, false, 0);
            return new QueryResponse(
                    requestId,
                    route,
                    resolveAllowModelSupplement(route),
                    List.of(),
                    resolveInstructions(route),
                    quality);
        }

        int originalHitCount = sources.size();
        boolean truncated = false;

        // 1. 按 score 降序排序
        List<EvidenceSource> sorted = sources.stream()
                .sorted(Comparator.comparingDouble(EvidenceSource::score).reversed())
                .toList();

        // 2. maxSources 截断
        int maxSources = queryConfig.getMaxSources();
        if (sorted.size() > maxSources) {
            sorted = sorted.subList(0, maxSources);
            truncated = true;
        }

        // 3. maxContentLength 单条截断
        int maxContentLength = queryConfig.getMaxContentLength();
        List<EvidenceSource> contentTruncated = new ArrayList<>(sorted.size());
        for (EvidenceSource src : sorted) {
            if (src.content() != null && src.content().length() > maxContentLength) {
                contentTruncated.add(new EvidenceSource(
                        src.dataset(),
                        src.title(),
                        src.content().substring(0, maxContentLength) + "[...]",
                        src.score(),
                        src.metadata()));
                truncated = true;
            } else {
                contentTruncated.add(src);
            }
        }

        // 4. maxTotalLength 总长度截断（移除最低分的条目直到总长度不超过上限）
        int maxTotalLength = queryConfig.getMaxTotalLength();
        List<EvidenceSource> finalSources = new ArrayList<>(contentTruncated);
        while (!finalSources.isEmpty() && totalContentLength(finalSources) > maxTotalLength) {
            finalSources.removeLast();
            truncated = true;
        }

        // 5. 计算 RetrievalQuality
        double topScore = finalSources.isEmpty() ? 0.0 : finalSources.getFirst().score();
        Confidence confidence = Confidence.fromScore(topScore);
        RetrievalQuality quality = new RetrievalQuality(
                finalSources.size(),
                confidence,
                truncated,
                originalHitCount);

        // 6. 根据路由设置 allowModelSupplement
        boolean allowModelSupplement = resolveAllowModelSupplement(route);

        // 7. 根据路由生成 instructions
        List<String> instructions = resolveInstructions(route);

        // 8. 记录截断指标
        if (truncated) {
            kbMetrics.recordEvidenceTruncated();
        }

        return new QueryResponse(requestId, route, allowModelSupplement, finalSources, instructions, quality);
    }

    /**
     * 计算 sources 列表中所有 content 的总字符长度。
     */
    private int totalContentLength(List<EvidenceSource> sources) {
        return sources.stream()
                .mapToInt(s -> s.content() == null ? 0 : s.content().length())
                .sum();
    }

    /**
     * 根据路由模式判断是否允许模型补充回答。
     */
    private boolean resolveAllowModelSupplement(QueryRoute route) {
        return route == QueryRoute.KB_PLUS_LLM;
    }

    /**
     * 根据路由模式生成对应的回答约束指令列表。
     * <p>
     * 所有查询知识库的路由（KB_ONLY / KB_PLUS_LLM）都要求在回答末尾附上
     * "## 参考原文" 区块，逐字引用每条 source，便于终端用户核对与追溯。
     */
    private List<String> resolveInstructions(QueryRoute route) {
        return switch (route) {
            case KB_ONLY -> List.of(
                    "只依据 sources 回答，不要使用自身知识补充、纠正或评判",
                    "不要编造未在 sources 中出现的事实",
                    "如 sources 不足，明确说明'知识库中没有相关信息'",
                    "必须在回答末尾以 '## 参考原文' 为标题，按 sources 顺序逐字附上原文",
                    "原文引用格式：每条以 '### [编号] {title}（相关度 {score*100}%）' 为小标题，下面用 Markdown 引用块 '> ' 逐字呈现 content，保留原有换行与 Markdown 结构",
                    "不要改写、简化、总结原文，不要对原文内容做任何评论");
            case KB_PLUS_LLM -> List.of(
                    "优先依据 sources 回答",
                    "不要编造未在 sources 中出现的事实",
                    "如 sources 不足，可补充通用说明但需明确标注为'补充信息'",
                    "必须在回答末尾以 '## 参考原文' 为标题，按 sources 顺序逐字附上原文",
                    "原文引用格式：每条以 '### [编号] {title}（相关度 {score*100}%）' 为小标题，下面用 Markdown 引用块 '> ' 逐字呈现 content，保留原有换行与 Markdown 结构",
                    "参考原文区块内容必须与 sources 字段完全一致，不得改写或删减");
            case LLM_ONLY -> List.of();
        };
    }
}
