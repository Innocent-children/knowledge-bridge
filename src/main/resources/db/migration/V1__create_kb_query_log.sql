-- kb_query_log（查询日志表）—— 第一阶段
-- 从 docs/schema.sql 提取

CREATE TABLE IF NOT EXISTS kb_query_log
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id         VARCHAR(64)   NOT NULL COMMENT '请求唯一标识',
    user_id            VARCHAR(64)   NOT NULL COMMENT '用户 ID',
    chat_id            VARCHAR(64)   NULL COMMENT '群聊 ID',
    session_key        VARCHAR(256)  NULL COMMENT '会话标识',
    question           TEXT          NOT NULL COMMENT '用户问题',
    route              VARCHAR(32)   NOT NULL COMMENT '路由结果',
    retrieval_quality  VARCHAR(16)   NULL COMMENT '命中置信度 (HIGH/MEDIUM/LOW)',
    source_count       INT           NULL COMMENT '返回证据条数',
    top_score          DECIMAL(5, 4) NULL COMMENT '最高命中分数',
    status             VARCHAR(32)   NOT NULL COMMENT '查询状态 (QueryStatus 枚举)',
    response_json      JSON          NULL COMMENT '完整响应（用于幂等缓存）',
    ragflow_latency_ms INT           NULL COMMENT 'RAGFlow 调用耗时(ms)',
    error_message      TEXT          NULL COMMENT '错误信息',
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    UNIQUE KEY uk_request_id (request_id),
    KEY idx_user_id (user_id),
    KEY idx_route (route),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT ='查询日志表';
