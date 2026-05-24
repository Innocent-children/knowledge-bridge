package com.openclaw.kbbridge.model.enums;

/**
 * 查询路由模式枚举。
 * <p>
 * 三种模式：
 * <ul>
 * <li>KB_ONLY — 只查知识库，回答严格基于检索结果</li>
 * <li>KB_PLUS_LLM — 结合知识库回答，知识库优先，LLM 可补充（默认）</li>
 * <li>LLM_ONLY — 完全不查知识库，由 LLM 自由回答</li>
 * </ul>
 */
public enum QueryRoute {
    /**
     * 只查知识库，回答严格基于检索结果，不允许 LLM 补充
     */
    KB_ONLY,
    /**
     * 结合知识库回答，知识库优先，LLM 可补充
     */
    KB_PLUS_LLM,
    /**
     * 完全不查知识库，由 LLM 自由回答
     */
    LLM_ONLY
}
