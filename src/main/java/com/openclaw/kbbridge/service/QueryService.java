package com.openclaw.kbbridge.service;

import com.openclaw.kbbridge.builder.EvidencePackBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.client.RagflowClient;
import com.openclaw.kbbridge.config.KbMetrics;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.dto.query.RetrievalQuality;
import com.openclaw.kbbridge.dto.ragflow.MemorySearchRequest;
import com.openclaw.kbbridge.dto.ragflow.MemorySearchResponse;
import com.openclaw.kbbridge.dto.ragflow.RetrievalRequest;
import com.openclaw.kbbridge.dto.ragflow.RetrievalResponse;
import com.openclaw.kbbridge.entity.QueryLogEntity;
import com.openclaw.kbbridge.exception.ExternalServiceException;
import com.openclaw.kbbridge.model.enums.Confidence;
import com.openclaw.kbbridge.model.enums.QueryRoute;
import com.openclaw.kbbridge.model.enums.QueryStatus;
import com.openclaw.kbbridge.repository.QueryLogMapper;
import com.openclaw.kbbridge.router.QueryRouter;
import com.openclaw.kbbridge.unified.UnifiedKnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 查询服务，编排查询链路的核心逻辑。
 * <p>
 * 负责记录查询日志、调用路由器、调用 RAGFlow 检索、去重排序、
 * 构建证据包、保存结果，以及在外部服务异常时执行降级策略。
 */
@Slf4j
@Service
public class QueryService {

    private final QueryRouter queryRouter;
    private final RagflowClient ragflowClient;
    private final EvidencePackBuilder evidencePackBuilder;
    private final QueryLogMapper queryLogMapper;
    private final KbProperties kbProperties;
    private final ObjectMapper objectMapper;
    private final KbMetrics kbMetrics;
    private UnifiedKnowledgeService unifiedKnowledgeService;
    @Autowired(required = false)
    public void setUnifiedKnowledgeService(UnifiedKnowledgeService service) { this.unifiedKnowledgeService = service; }

    public QueryService(QueryRouter queryRouter,
            RagflowClient ragflowClient,
            EvidencePackBuilder evidencePackBuilder,
            QueryLogMapper queryLogMapper,
            KbProperties kbProperties,
            ObjectMapper objectMapper,
            KbMetrics kbMetrics) {
        this.queryRouter = queryRouter;
        this.ragflowClient = ragflowClient;
        this.evidencePackBuilder = evidencePackBuilder;
        this.queryLogMapper = queryLogMapper;
        this.kbProperties = kbProperties;
        this.objectMapper = objectMapper;
        this.kbMetrics = kbMetrics;
    }

    /**
     * 执行查询，返回知识证据包。
     *
     * @param request 查询请求
     * @return 结构化的查询响应（证据包）
     */
    public boolean usesUnifiedKnowledge() { return unifiedKnowledgeService != null; }

    public QueryResponse query(QueryRequest request) {
        // 1. 记录查询日志（状态 QUERY_RECEIVED）
        QueryLogEntity logEntity = createQueryLog(request);
        if (unifiedKnowledgeService != null) {
            QueryLogEntity existing = queryLogMapper.selectOne(new LambdaQueryWrapper<QueryLogEntity>()
                    .eq(QueryLogEntity::getRequestId, logEntity.getRequestId()));
            if (existing != null) logEntity.setId(existing.getId());
        }
        if (logEntity.getId() == null) queryLogMapper.insert(logEntity);
        else queryLogMapper.updateById(logEntity);

        try {
            // 2. 路由判定
            QueryRoute route = queryRouter.route(request);
            logEntity.setRoute(route.name());
            logEntity.setStatus(routeToStatus(route).name());
            kbMetrics.recordQuery(route);

            // 3. LLM_ONLY 直接返回空证据包
            if (route == QueryRoute.LLM_ONLY) {
                QueryResponse response = buildEmptyResponse(request.requestId(), route);
                saveSuccessResult(logEntity, response);
                return response;
            }

            // Unified retrieval filters the current effective release in the administrator knowledge base.
            List<EvidenceSource> sources = new ArrayList<>();
            if (unifiedKnowledgeService != null) {
                sources.addAll(unifiedKnowledgeService.legacyQuery(request.question(),
                        kbProperties.getQuery().getMaxSources()));
            }
            // Historical RAGFlow access requires an explicit dataset allowlist.
            List<String> legacyDatasets = legacyDatasetIds();
            if (unifiedKnowledgeService == null || !legacyDatasets.isEmpty()) {
                long startTime = System.currentTimeMillis();
                RetrievalResponse retrievalResponse = ragflowClient.retrieval(
                        buildRetrievalRequest(request), request.requestId());
                long latencyMs = System.currentTimeMillis() - startTime;
                logEntity.setRagflowLatencyMs((int) latencyMs);
                kbMetrics.recordRagflowLatency(latencyMs);
                List<EvidenceSource> legacySources = convertAndDedup(retrievalResponse);
                if (unifiedKnowledgeService != null) {
                    legacySources = legacySources.stream().filter(source -> legacyDatasets.contains(source.dataset())).toList();
                }
                sources.addAll(legacySources);
            }
            sources = dedup(sources);

            // 5.1 Memory 检索集成（KB_ONLY / KB_PLUS_LLM 路由时生效）
            if (kbProperties.getQuery().isMemoryEnabled()
                    && (route == QueryRoute.KB_ONLY || route == QueryRoute.KB_PLUS_LLM)) {
                List<EvidenceSource> memorySources = searchMemory(request);
                if (!memorySources.isEmpty()) {
                    List<EvidenceSource> merged = new ArrayList<>(sources);
                    merged.addAll(memorySources);
                    sources = merged;
                    // 重新去重（Memory 结果可能与检索结果重复）
                    sources = dedup(sources);
                }
            }

            // 6. 构建证据包（含排序、截断、置信度分类）
            QueryResponse response = evidencePackBuilder.build(request.requestId(), route, sources);

            // 6.1 记录命中数和 top1 score 指标
            kbMetrics.recordHitCount(response.sources().size());
            if (!response.sources().isEmpty()) {
                kbMetrics.recordTopScore(response.sources().getFirst().score());
            }

            // 7. 低置信度时更新状态
            if (response.retrievalQuality().confidence() == Confidence.LOW) {
                logEntity.setStatus(QueryStatus.RETRIEVAL_LOW_CONFIDENCE.name());
            }

            // 8. 保存成功结果
            saveSuccessResult(logEntity, response);
            return response;

        } catch (ExternalServiceException ex) {
            // 降级策略：根据路由模式返回降级响应
            kbMetrics.recordQueryFailed();
            return handleDegradation(logEntity, request, ex);
        } catch (Exception ex) {
            // 不可恢复异常：标记 ANSWER_FAILED
            kbMetrics.recordQueryFailed();
            logEntity.setStatus(QueryStatus.ANSWER_FAILED.name());
            logEntity.setErrorMessage(truncateMessage(ex.getMessage()));
            queryLogMapper.updateById(logEntity);
            throw ex;
        }
    }

    /**
     * 创建初始查询日志实体。
     */
    private QueryLogEntity createQueryLog(QueryRequest request) {
        QueryLogEntity entity = new QueryLogEntity();
        entity.setRequestId(request.requestId());
        entity.setUserId(request.userId());
        entity.setChatId(request.chatId());
        entity.setSessionKey(request.sessionKey());
        entity.setQuestion(request.question());
        entity.setRoute("PENDING");
        entity.setStatus(QueryStatus.QUERY_RECEIVED.name());
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    /**
     * 将 QueryRoute 映射为对应的 QueryStatus。
     */
    QueryStatus routeToStatus(QueryRoute route) {
        return switch (route) {
            case LLM_ONLY -> QueryStatus.ROUTE_LLM_ONLY;
            case KB_ONLY -> QueryStatus.ROUTE_KB_ONLY;
            case KB_PLUS_LLM -> QueryStatus.ROUTE_KB_PLUS_LLM;
        };
    }

    /**
     * 构建 LLM_ONLY 路由的空证据包响应。
     */
    private QueryResponse buildEmptyResponse(String requestId, QueryRoute route) {
        RetrievalQuality quality = new RetrievalQuality(0, Confidence.LOW, false, 0);
        return new QueryResponse(requestId, route, false, List.of(), List.of(), quality);
    }

    /**
     * 根据查询请求和配置构建 RAGFlow 检索请求。
     * <p>
     * 数据集选择优先级：
     * <ol>
     * <li>kb.query.dataset-ids 配置（如果非空）</li>
     * <li>kb.ragflow.dataset-id 配置（回退）</li>
     * </ol>
     * 当 kb.query.metadata-filter-enabled=true 时，添加 reviewStatus=APPROVED 过滤条件。
     * </p>
     */
    private RetrievalRequest buildRetrievalRequest(QueryRequest request) {
        KbProperties.Query queryConfig = kbProperties.getQuery();

        List<String> datasetIds = legacyDatasetIds();

        // metadata 过滤条件
        Map<String, Object> metadataCondition = null;
        if (queryConfig.isMetadataFilterEnabled()) {
            metadataCondition = Map.of("reviewStatus", "APPROVED");
        }

        return new RetrievalRequest(
                request.question(),
                datasetIds,
                queryConfig.getMaxSources(),
                queryConfig.getScoreThreshold(),
                metadataCondition);
    }

    private List<String> legacyDatasetIds() {
        List<String> configured = kbProperties.getQuery().getDatasetIds();
        if (configured == null || configured.isEmpty()) {
            String fallback = kbProperties.getRagflow().getDatasetId();
            configured = fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
        }
        return configured.stream().filter(Objects::nonNull).map(String::trim)
                .filter(id -> !id.isBlank())
                .distinct().toList();
    }

    /**
     * 将 RAGFlow 检索结果转换为 EvidenceSource 列表，并按 content 去重。
     * <p>
     * 使用 content 字符串作为去重键，保留首次出现的条目（即 score 较高的，
     * 因为 RAGFlow 通常按 score 降序返回）。
     */
    List<EvidenceSource> convertAndDedup(RetrievalResponse retrievalResponse) {
        if (retrievalResponse == null || retrievalResponse.chunks() == null) {
            return List.of();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<EvidenceSource> result = new ArrayList<>();

        for (RetrievalResponse.Chunk chunk : retrievalResponse.chunks()) {
            String contentKey = chunk.content() != null ? chunk.content() : "";
            if (seen.add(contentKey)) {
                result.add(new EvidenceSource(
                        chunk.datasetId(),
                        chunk.documentName(),
                        chunk.content(),
                        chunk.score(),
                        chunk.metadata()));
            }
        }
        return result;
    }

    /**
     * 调用 RAGFlow Memory 检索，将结果转换为 EvidenceSource 列表。
     * <p>
     * Memory 检索失败时仅记录警告日志，不影响主查询流程。
     */
    List<EvidenceSource> searchMemory(QueryRequest request) {
        try {
            long memStart = System.currentTimeMillis();
            List<String> allowedDatasets = legacyDatasetIds();
            if (unifiedKnowledgeService != null && allowedDatasets.isEmpty()) return List.of();
            // Memory is restricted to the explicitly configured historical dataset.
            String datasetId = allowedDatasets.isEmpty() ? null : allowedDatasets.getFirst();
            MemorySearchRequest memoryRequest = new MemorySearchRequest(request.question(), datasetId);
            MemorySearchResponse memoryResponse = ragflowClient.searchMemory(memoryRequest, request.requestId());
            long memLatencyMs = System.currentTimeMillis() - memStart;

            if (memoryResponse == null || memoryResponse.chunks() == null
                    || memoryResponse.chunks().isEmpty()) {
                log.debug("Memory 检索无结果, requestId={}, latencyMs={}",
                        request.requestId(), memLatencyMs);
                return List.of();
            }

            List<EvidenceSource> memorySources = new ArrayList<>();
            for (MemorySearchResponse.MemoryChunk chunk : memoryResponse.chunks()) {
                memorySources.add(new EvidenceSource(
                        "memory",
                        "memory",
                        chunk.content(),
                        chunk.score(),
                        Map.of("source", "memory")));
            }

            log.info("Memory 检索完成, requestId={}, count={}, latencyMs={}",
                    request.requestId(), memorySources.size(), memLatencyMs);
            return memorySources;
        } catch (Exception ex) {
            log.warn("Memory 检索失败，继续使用主检索结果, requestId={}, error={}",
                    request.requestId(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 对 EvidenceSource 列表按 content 去重，保留首次出现的条目。
     */
    List<EvidenceSource> dedup(List<EvidenceSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<EvidenceSource> result = new ArrayList<>();
        for (EvidenceSource source : sources) {
            String contentKey = source.content() != null ? source.content() : "";
            if (seen.add(contentKey)) {
                result.add(source);
            }
        }
        return result;
    }

    /**
     * 保存成功的查询结果到日志。
     */
    private void saveSuccessResult(QueryLogEntity logEntity, QueryResponse response) {
        // 最终状态设为 ANSWERED（除非已被设为 RETRIEVAL_LOW_CONFIDENCE）
        if (!QueryStatus.RETRIEVAL_LOW_CONFIDENCE.name().equals(logEntity.getStatus())) {
            logEntity.setStatus(QueryStatus.ANSWERED.name());
        }

        logEntity.setRetrievalQuality(response.retrievalQuality().confidence().name());
        logEntity.setSourceCount(response.sources().size());

        if (!response.sources().isEmpty()) {
            logEntity.setTopScore(BigDecimal.valueOf(response.sources().getFirst().score()));
        }

        try {
            logEntity.setResponseJson(objectMapper.writeValueAsString(response));
        } catch (Exception ex) {
            log.warn("序列化查询响应失败, requestId={}", logEntity.getRequestId(), ex);
        }

        queryLogMapper.updateById(logEntity);
    }

    /**
     * 处理外部服务异常时的降级策略。
     * <p>
     * - KB_ONLY: 返回空 sources + "知识库暂无可靠答案" 声明
     * - KB_PLUS_LLM: 返回空 sources + LOW 置信度 + allowModelSupplement=true
     */
    private QueryResponse handleDegradation(QueryLogEntity logEntity, QueryRequest request,
            ExternalServiceException ex) {
        log.warn("外部服务异常，执行降级策略, requestId={}, service={}, message={}",
                request.requestId(), ex.getServiceName(), ex.getMessage());

        String routeName = logEntity.getRoute();
        QueryRoute route = routeName != null ? QueryRoute.valueOf(routeName) : QueryRoute.KB_PLUS_LLM;

        QueryResponse degradedResponse = switch (route) {
            case KB_ONLY -> buildKbOnlyDegradation(request.requestId(), route);
            case KB_PLUS_LLM -> buildKbPlusLlmDegradation(request.requestId(), route);
            case LLM_ONLY -> buildEmptyResponse(request.requestId(), route);
        };

        logEntity.setStatus(QueryStatus.ANSWER_FAILED.name());
        logEntity.setErrorMessage(truncateMessage(ex.getMessage()));
        logEntity.setRetrievalQuality(Confidence.LOW.name());
        logEntity.setSourceCount(0);
        logEntity.setTopScore(null);
        try {
            logEntity.setResponseJson(objectMapper.writeValueAsString(degradedResponse));
        } catch (Exception jsonEx) {
            log.warn("Serialize degraded response failed, requestId={}", logEntity.getRequestId(), jsonEx);
        }
        queryLogMapper.updateById(logEntity);
        return degradedResponse;
    }

    /**
     * KB_ONLY 降级：返回空 sources + "知识库暂无可靠答案" 声明。
     */
    private QueryResponse buildKbOnlyDegradation(String requestId, QueryRoute route) {
        RetrievalQuality quality = new RetrievalQuality(0, Confidence.LOW, false, 0);
        List<String> instructions = List.of("知识库暂无可靠答案");
        return new QueryResponse(requestId, route, false, List.of(), instructions, quality);
    }

    /**
     * KB_PLUS_LLM 降级：返回空 sources + LOW 置信度 + allowModelSupplement=true。
     */
    private QueryResponse buildKbPlusLlmDegradation(String requestId, QueryRoute route) {
        RetrievalQuality quality = new RetrievalQuality(0, Confidence.LOW, false, 0);
        List<String> instructions = List.of(
                "优先依据 sources 回答",
                "不要编造未在 sources 中出现的事实",
                "如 sources 不足，可补充通用说明但需标注为补充");
        return new QueryResponse(requestId, route, true, List.of(), instructions, quality);
    }

    /**
     * 截断错误消息，避免过长内容写入数据库。
     */
    private String truncateMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
