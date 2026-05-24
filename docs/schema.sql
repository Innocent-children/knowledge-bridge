-- ============================================================
-- Knowledge Bridge 数据库初始化脚本 (MySQL 8.0+)
-- ============================================================

CREATE DATABASE IF NOT EXISTS kb_bridge
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE kb_bridge;

-- ============================================================
-- 1. kb_query_log（查询日志表）—— 第一阶段
-- ============================================================
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

-- ============================================================
-- 2. kb_ingest_task（入库任务表）—— 第二阶段
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_ingest_task
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64)  NOT NULL COMMENT '请求唯一标识',
    source_channel      VARCHAR(32)  NOT NULL COMMENT '来源渠道 (feishu 等)',
    source_type         VARCHAR(32)  NOT NULL COMMENT '来源类型 (FEISHU_CHAT/MARKDOWN/ATTACHMENT)',
    user_id             VARCHAR(64)  NOT NULL COMMENT '用户 ID',
    chat_id             VARCHAR(64)  NULL COMMENT '群聊 ID',
    message_ids_json    JSON         NULL COMMENT '关联的消息 ID 列表',
    status              VARCHAR(32)  NOT NULL COMMENT '入库状态 (DocumentStatus 枚举)',
    review_status       VARCHAR(32)  NOT NULL COMMENT '审核状态 (ReviewStatus 枚举)',
    raw_object_key      VARCHAR(512) NULL COMMENT 'MinIO 原始件路径',
    processed_guide_key VARCHAR(512) NULL COMMENT 'MinIO Guide 处理件路径',
    processed_qa_key    VARCHAR(512) NULL COMMENT 'MinIO Q&A 处理件路径',
    content_hash        VARCHAR(64)  NOT NULL COMMENT '原始内容 SHA-256',
    processor_version   VARCHAR(16)  NULL COMMENT '处理器版本',
    error_message       TEXT         NULL COMMENT '错误信息',
    retry_count         INT          NOT NULL DEFAULT 0 COMMENT '补偿重试次数',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_request_id (request_id),
    KEY idx_content_hash (content_hash),
    KEY idx_user_id (user_id),
    KEY idx_status (status),
    KEY idx_review_status (review_status),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT ='入库任务表';

-- ============================================================
-- 3. kb_document（知识文档表）—— 第三阶段
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_document
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id             BIGINT       NOT NULL COMMENT '关联入库任务 ID',
    knowledge_type      VARCHAR(16)  NOT NULL COMMENT '知识类型 (GUIDE/QA)',
    title               VARCHAR(256) NOT NULL COMMENT '文档标题',
    topic               VARCHAR(128) NULL COMMENT '主题',
    tags_json           JSON         NULL COMMENT '标签列表',
    review_status       VARCHAR(32)  NOT NULL COMMENT '审核状态',
    status              VARCHAR(32)  NOT NULL COMMENT '文档状态',
    dataset_name        VARCHAR(128) NULL COMMENT 'RAGFlow Dataset 名称',
    ragflow_document_id VARCHAR(128) NULL COMMENT 'RAGFlow 文档 ID',
    metadata_json       JSON         NULL COMMENT '完整元数据',
    version             INT          NOT NULL DEFAULT 1 COMMENT '文档版本号',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    KEY idx_task_id (task_id),
    KEY idx_knowledge_type (knowledge_type),
    KEY idx_topic (topic),
    KEY idx_status (status),
    KEY idx_review_status (review_status),
    KEY idx_ragflow_document_id (ragflow_document_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT ='知识文档表';

-- ============================================================
-- 4. kb_review_task（审核任务表）—— 第三阶段
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_review_task
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id       BIGINT      NOT NULL COMMENT '关联入库任务 ID',
    review_status VARCHAR(32) NOT NULL COMMENT '审核状态',
    reviewer      VARCHAR(64) NULL COMMENT '审核人',
    comment       TEXT        NULL COMMENT '审核意见',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    KEY idx_task_id (task_id),
    KEY idx_review_status (review_status),
    KEY idx_reviewer (reviewer)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT ='审核任务表';
