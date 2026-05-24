package com.openclaw.kbbridge.config;

import com.openclaw.kbbridge.model.enums.QueryRoute;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 核心业务指标采集组件。
 * <p>
 * 通过 Micrometer 采集以下核心指标：
 * <ul>
 * <li>查询 QPS、查询失败率</li>
 * <li>RAGFlow 调用耗时（P50/P95/P99）</li>
 * <li>入库成功率、LLM 重写成功率、质量校验通过率、处理失败率</li>
 * <li>平均命中数、平均 top1 score、证据包截断率</li>
 * </ul>
 */
@Slf4j
@Component
public class KbMetrics {

    // ── Counters ──

    private final Counter queryFailedCounter;
    private final Counter ingestTotalCounter;
    private final Counter ingestSuccessCounter;
    private final Counter ingestFailedCounter;
    private final Counter llmRewriteTotalCounter;
    private final Counter llmRewriteSuccessCounter;
    private final Counter qualityCheckTotalCounter;
    private final Counter qualityCheckPassedCounter;
    private final Counter evidenceTruncatedCounter;

    // ── Timer ──

    private final Timer ragflowLatencyTimer;

    // ── Distribution Summaries ──

    private final DistributionSummary hitCountSummary;
    private final DistributionSummary topScoreSummary;

    // ── MeterRegistry for tagged counters ──

    private final MeterRegistry meterRegistry;

    public KbMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Counters
        this.queryFailedCounter = Counter.builder("kb.query.failed")
                .description("Total failed queries")
                .register(meterRegistry);

        this.ingestTotalCounter = Counter.builder("kb.ingest.total")
                .description("Total ingest tasks")
                .register(meterRegistry);

        this.ingestSuccessCounter = Counter.builder("kb.ingest.success")
                .description("Successful ingest tasks")
                .register(meterRegistry);

        this.ingestFailedCounter = Counter.builder("kb.ingest.failed")
                .description("Failed ingest tasks")
                .register(meterRegistry);

        this.llmRewriteTotalCounter = Counter.builder("kb.llm.rewrite.total")
                .description("Total LLM rewrite attempts")
                .register(meterRegistry);

        this.llmRewriteSuccessCounter = Counter.builder("kb.llm.rewrite.success")
                .description("Successful LLM rewrites")
                .register(meterRegistry);

        this.qualityCheckTotalCounter = Counter.builder("kb.quality.check.total")
                .description("Total quality checks")
                .register(meterRegistry);

        this.qualityCheckPassedCounter = Counter.builder("kb.quality.check.passed")
                .description("Passed quality checks")
                .register(meterRegistry);

        this.evidenceTruncatedCounter = Counter.builder("kb.evidence.truncated")
                .description("Evidence packs that were truncated")
                .register(meterRegistry);

        // Timer (automatically provides P50/P95/P99 via Micrometer percentile
        // histograms)
        this.ragflowLatencyTimer = Timer.builder("kb.ragflow.latency")
                .description("RAGFlow call latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // Distribution Summaries
        this.hitCountSummary = DistributionSummary.builder("kb.query.hit.count")
                .description("Distribution of hit counts per query")
                .register(meterRegistry);

        this.topScoreSummary = DistributionSummary.builder("kb.query.top.score")
                .description("Distribution of top1 scores per query")
                .register(meterRegistry);
    }

    /**
     * 记录查询请求（按路由分类）。
     *
     * @param route 查询路由
     */
    public void recordQuery(QueryRoute route) {
        Counter.builder("kb.query.total")
                .tag("route", route.name())
                .description("Total queries")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录查询失败。
     */
    public void recordQueryFailed() {
        queryFailedCounter.increment();
    }

    /**
     * 记录 RAGFlow 调用耗时。
     *
     * @param latencyMs 耗时（毫秒）
     */
    public void recordRagflowLatency(long latencyMs) {
        ragflowLatencyTimer.record(Duration.ofMillis(latencyMs));
    }

    /**
     * 记录入库任务总数。
     */
    public void recordIngestTotal() {
        ingestTotalCounter.increment();
    }

    /**
     * 记录入库成功。
     */
    public void recordIngestSuccess() {
        ingestSuccessCounter.increment();
    }

    /**
     * 记录入库失败。
     */
    public void recordIngestFailed() {
        ingestFailedCounter.increment();
    }

    /**
     * 记录 LLM 重写总数。
     */
    public void recordLlmRewriteTotal() {
        llmRewriteTotalCounter.increment();
    }

    /**
     * 记录 LLM 重写成功。
     */
    public void recordLlmRewriteSuccess() {
        llmRewriteSuccessCounter.increment();
    }

    /**
     * 记录质量校验总数。
     */
    public void recordQualityCheckTotal() {
        qualityCheckTotalCounter.increment();
    }

    /**
     * 记录质量校验通过。
     */
    public void recordQualityCheckPassed() {
        qualityCheckPassedCounter.increment();
    }

    /**
     * 记录命中数。
     *
     * @param count 命中数量
     */
    public void recordHitCount(int count) {
        hitCountSummary.record(count);
    }

    /**
     * 记录 top1 score。
     *
     * @param score top1 分数
     */
    public void recordTopScore(double score) {
        topScoreSummary.record(score);
    }

    /**
     * 记录证据包截断。
     */
    public void recordEvidenceTruncated() {
        evidenceTruncatedCounter.increment();
    }
}
