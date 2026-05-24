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
