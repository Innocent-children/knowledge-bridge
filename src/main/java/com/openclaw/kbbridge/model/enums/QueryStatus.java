package com.openclaw.kbbridge.model.enums;

/**
 * 查询状态枚举，追踪查询处理的完整生命周期。
 */
public enum QueryStatus {
    /**
     * 已接收查询请求
     */
    QUERY_RECEIVED,
    /**
     * 路由判定为不查知识库
     */
    ROUTE_LLM_ONLY,
    /**
     * 路由判定为只查知识库
     */
    ROUTE_KB_ONLY,
    /**
     * 路由判定为结合知识库回答
     */
    ROUTE_KB_PLUS_LLM,
    /**
     * 检索结果置信度低
     */
    RETRIEVAL_LOW_CONFIDENCE,
    /**
     * 查询已完成回答
     */
    ANSWERED,
    /**
     * 查询回答失败
     */
    ANSWER_FAILED
}
