-- kb_ingest_task（入库任务表）—— 第二阶段
-- 从 docs/schema.sql 提取

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
