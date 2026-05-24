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
