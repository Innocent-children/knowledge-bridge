package com.openclaw.kbbridge.router;

import com.openclaw.kbbridge.dto.query.QueryRequest;
import com.openclaw.kbbridge.model.enums.QueryRoute;

/**
 * 查询路由策略接口。
 * <p>
 * 通过策略模式对用户问题进行路由判定，决定本次查询是否需要查询知识库以及查询模式。
 * 实现类包括 {@link RuleBasedRouteStrategy}（基于 YAML 配置规则）和
 * LlmRouteStrategy（基于 LLM 意图分类，后续版本实现）。
 */
public interface QueryRouteStrategy {

    /**
     * 根据查询请求解析路由结果。
     *
     * @param request 查询请求
     * @return 路由结果枚举
     */
    QueryRoute resolve(QueryRequest request);
}
