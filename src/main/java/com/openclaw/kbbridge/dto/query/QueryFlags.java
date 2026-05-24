package com.openclaw.kbbridge.dto.query;

/**
 * 查询控制标志。
 *
 * @param strictKbOnly 是否只基于知识库回答（优先级最高，等同于 KB_ONLY 路由）
 * @param needCitation 是否需要引用来源
 */
public record QueryFlags(
                // 是否严格仅基于知识库回答；true 时等同于 KB_ONLY 路由，优先级高于所有路由规则
                boolean strictKbOnly,
                // 是否需要在回答中附带引用来源（evidence/citations）
                boolean needCitation) {

        /**
         * 兼容旧的三参数构造（forceKb 已废弃，忽略）。
         */
        public QueryFlags(boolean forceKb, boolean needCitation, boolean strictKbOnly) {
                this(strictKbOnly, needCitation);
        }
}
