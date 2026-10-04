package com.openclaw.kbbridge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.dto.query.QueryResponse;
import com.openclaw.kbbridge.entity.QueryLogEntity;
import com.openclaw.kbbridge.repository.QueryLogMapper;
import com.openclaw.kbbridge.service.QueryService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 查询控制器。
 * <p>
 * 提供 POST /api/v1/query 端点，接收 OpenClaw 转发的查询请求，
 * 执行参数校验、requestId 幂等检查，调用 QueryService 返回知识证据包。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final QueryService queryService;
    private final QueryLogMapper queryLogMapper;
    private final ObjectMapper objectMapper;

    public QueryController(QueryService queryService,
                           QueryLogMapper queryLogMapper,
                           ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.queryLogMapper = queryLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询知识证据包。
     * <p>
     * 接收查询请求，先通过 requestId 做幂等检查：
     * 若 kb_query_log 中已有该 requestId 且 response_json 非空，则直接返回缓存结果；
     * 否则调用 QueryService 执行查询。
     * </p>
     *
     * @param request 查询请求（requestId、userId、question 必填）
     * @return 知识证据包响应
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        // 1. 幂等检查：按 requestId 查询 kb_query_log
        QueryLogEntity existing = queryLogMapper.selectOne(
                new LambdaQueryWrapper<QueryLogEntity>()
                        .eq(QueryLogEntity::getRequestId, request.requestId()));

        // Unified queries re-check effective releases on every request, including retries.
        if (!queryService.usesUnifiedKnowledge() && existing != null && existing.getResponseJson() != null
                && !existing.getResponseJson().isBlank()) {
            log.info("幂等命中，返回缓存结果, requestId={}", request.requestId());
            try {
                QueryResponse cached = objectMapper.readValue(
                        existing.getResponseJson(), QueryResponse.class);
                return ResponseEntity.ok(cached);
            } catch (Exception ex) {
                log.warn("反序列化缓存响应失败，重新执行查询, requestId={}", request.requestId(), ex);
            }
        }

        // 2. 调用 QueryService 执行查询
        QueryResponse response = queryService.query(request);

        // 3. 返回响应
        return ResponseEntity.ok(response);
    }
}
