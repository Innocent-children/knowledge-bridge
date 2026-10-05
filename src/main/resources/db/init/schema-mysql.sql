-- Knowledge Bridge current schema (through V7), MySQL 8.4.
-- Select a newly created empty database before importing this file.
-- This file creates all five tables directly; no ALTER, seed data, or account creation.
-- Do not combine it with V1-V7. Existing databases use the documented upgrade path.
-- Application credentials, MinIO buckets and external services are configured separately.

CREATE TABLE kb_query_log
(
    id                   BIGINT         AUTO_INCREMENT PRIMARY KEY,
    request_id           VARCHAR(64)    NOT NULL COMMENT '请求唯一标识',
    user_id              VARCHAR(64)    NOT NULL COMMENT '用户 ID',
    chat_id              VARCHAR(64)    NULL COMMENT '群聊 ID',
    session_key          VARCHAR(256)   NULL COMMENT '会话标识',
    question             TEXT           NOT NULL COMMENT '用户问题',
    route                VARCHAR(32)    NOT NULL COMMENT '路由结果',
    retrieval_quality    VARCHAR(16)    NULL COMMENT '命中置信度 (HIGH/MEDIUM/LOW)',
    source_count         INT            NULL COMMENT '返回证据条数',
    top_score            DECIMAL(5, 4)  NULL COMMENT '最高命中分数',
    status               VARCHAR(32)    NOT NULL COMMENT '查询状态 (QueryStatus 枚举)',
    response_json        JSON           NULL COMMENT '完整响应（用于幂等缓存）',
    ragflow_latency_ms   INT            NULL COMMENT 'RAGFlow 调用耗时(ms)',
    error_message        TEXT           NULL COMMENT '错误信息',
    created_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    UNIQUE KEY uk_request_id (request_id),
    KEY idx_user_id (user_id),
    KEY idx_route (route),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT ='查询日志表';

CREATE TABLE kb_ingest_task
(
    id                   BIGINT         AUTO_INCREMENT PRIMARY KEY,
    request_id           VARCHAR(64)    NOT NULL COMMENT '请求唯一标识',
    source_channel       VARCHAR(32)    NOT NULL COMMENT '来源渠道 (feishu 等)',
    source_type          VARCHAR(32)    NOT NULL COMMENT '来源类型 (FEISHU_CHAT/MARKDOWN/ATTACHMENT)',
    user_id              VARCHAR(64)    NOT NULL COMMENT '用户 ID',
    chat_id              VARCHAR(64)    NULL COMMENT '群聊 ID',
    message_ids_json     JSON           NULL COMMENT '关联的消息 ID 列表',
    status               VARCHAR(32)    NOT NULL COMMENT '入库状态 (DocumentStatus 枚举)',
    review_status        VARCHAR(32)    NOT NULL COMMENT '审核状态 (ReviewStatus 枚举)',
    raw_object_key       VARCHAR(512)   NULL COMMENT 'MinIO 原始件路径',
    processed_guide_key  VARCHAR(512)   NULL COMMENT 'MinIO Guide 处理件路径',
    processed_qa_key     VARCHAR(512)   NULL COMMENT 'MinIO Q&A 处理件路径',
    content_hash         VARCHAR(64)    NOT NULL COMMENT '原始内容 SHA-256',
    processor_version    VARCHAR(16)    NULL COMMENT '处理器版本',
    error_message        TEXT           NULL COMMENT '错误信息',
    created_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    file_name            VARCHAR(512)   NULL,
    mime_type            VARCHAR(255)   NULL,
    file_size            BIGINT         NULL,
    source_message_id    VARCHAR(255)   NULL,
    external_document_id VARCHAR(255)   NULL,
    external_job_id      VARCHAR(255)   NULL,
    error_code           VARCHAR(64)    NULL,
    retryable            BOOLEAN        NULL,
    retry_count          INT            NOT NULL DEFAULT 0,
    document_id          CHAR(36)       NULL,
    release_id           CHAR(36)       NULL,
    operation            VARCHAR(16)    NULL,
    source_rev_no        BIGINT         NULL,
    publish_seq          BIGINT         NULL,
    payload_json         LONGTEXT       NULL,
    request_fingerprint  CHAR(64)       NULL,
    lease_owner          VARCHAR(64)    NULL,
    lease_token          BIGINT         NOT NULL DEFAULT 0,
    lease_until          TIMESTAMP(3)   NULL,
    next_run_at          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    attempts             INT            NOT NULL DEFAULT 0,
    KEY ix_unified_jobs(operation,status,next_run_at),
    KEY ix_unified_doc(document_id,publish_seq),

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

CREATE TABLE kb_document
(
    id                   BIGINT         AUTO_INCREMENT PRIMARY KEY,
    task_id              BIGINT         NOT NULL COMMENT '关联入库任务 ID',
    knowledge_type       VARCHAR(16)    NOT NULL COMMENT '知识类型 (GUIDE/QA)',
    title                VARCHAR(256)   NOT NULL COMMENT '文档标题',
    topic                VARCHAR(128)   NULL COMMENT '主题',
    tags_json            JSON           NULL COMMENT '标签列表',
    review_status        VARCHAR(32)    NOT NULL COMMENT '审核状态',
    status               VARCHAR(32)    NOT NULL COMMENT '文档状态',
    dataset_name         VARCHAR(128)   NULL COMMENT 'RAGFlow Dataset 名称',
    ragflow_document_id  VARCHAR(128)   NULL COMMENT 'RAGFlow 文档 ID',
    metadata_json        JSON           NULL COMMENT '完整元数据',
    version              INT            NOT NULL DEFAULT 1 COMMENT '文档版本号',
    created_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    document_id          CHAR(36)       NULL,
    release_id           CHAR(36)       NULL,
    publish_seq          BIGINT         NULL,
    source_rev_no        BIGINT         NULL,
    content_type         VARCHAR(16)    NULL,
    object_key           VARCHAR(512)   NULL,
    sha256               CHAR(64)       NULL,
    UNIQUE KEY uq_release(release_id),
    KEY ix_unified_release(document_id,publish_seq),

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

CREATE TABLE kb_review_task
(
    id                   BIGINT         AUTO_INCREMENT PRIMARY KEY,
    task_id              BIGINT         NOT NULL COMMENT '关联入库任务 ID',
    review_status        VARCHAR(32)    NOT NULL COMMENT '审核状态',
    reviewer             VARCHAR(64)    NULL COMMENT '审核人',
    comment              TEXT           NULL COMMENT '审核意见',
    created_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    KEY idx_task_id (task_id),
    KEY idx_review_status (review_status),
    KEY idx_reviewer (reviewer)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT ='审核任务表';

CREATE TABLE kb_logical_document (
  document_id CHAR(36) NOT NULL PRIMARY KEY,
  source_ref VARCHAR(256) NOT NULL,
  source VARCHAR(16) NOT NULL,
  source_rev_no BIGINT NOT NULL DEFAULT 0,
  desired_seq BIGINT NOT NULL DEFAULT 0,
  desired_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  effective_release_id CHAR(36) NULL,
  cleanup_complete BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_source_ref(source,source_ref),
  KEY ix_effective(effective_release_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
