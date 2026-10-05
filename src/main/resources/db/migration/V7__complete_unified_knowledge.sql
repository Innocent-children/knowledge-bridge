-- MySQL 8.4; apply to a V1-V5 bridge database to complete the V6 schema.
-- Use this file instead of replaying V6. V1-V6 are retained unchanged.
-- Also supports an interrupted V6 and a fully applied V6 (no schema changes).
-- Existing columns, indexes, rows and retry_count values are preserved.
-- Requires the V1-V5 tables/fields and a selected database; no object or row migration.

CREATE TABLE IF NOT EXISTS kb_logical_document (
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

SET @kb_unified_old_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 8192;

-- Add only the known V6 columns and indexes that are absent from kb_ingest_task.
SELECT GROUP_CONCAT(required.addition ORDER BY required.position SEPARATOR ', ')
INTO @kb_unified_additions
FROM (
    SELECT 1 AS position, 'column' AS object_kind, 'retry_count' AS object_name, 'ADD COLUMN retry_count INT NOT NULL DEFAULT 0' AS addition
    UNION ALL
    SELECT 2, 'column', 'document_id', 'ADD COLUMN document_id CHAR(36) NULL'
    UNION ALL
    SELECT 3, 'column', 'release_id', 'ADD COLUMN release_id CHAR(36) NULL'
    UNION ALL
    SELECT 4, 'column', 'operation', 'ADD COLUMN operation VARCHAR(16) NULL'
    UNION ALL
    SELECT 5, 'column', 'source_rev_no', 'ADD COLUMN source_rev_no BIGINT NULL'
    UNION ALL
    SELECT 6, 'column', 'publish_seq', 'ADD COLUMN publish_seq BIGINT NULL'
    UNION ALL
    SELECT 7, 'column', 'payload_json', 'ADD COLUMN payload_json LONGTEXT NULL'
    UNION ALL
    SELECT 8, 'column', 'request_fingerprint', 'ADD COLUMN request_fingerprint CHAR(64) NULL'
    UNION ALL
    SELECT 9, 'column', 'lease_owner', 'ADD COLUMN lease_owner VARCHAR(64) NULL'
    UNION ALL
    SELECT 10, 'column', 'lease_token', 'ADD COLUMN lease_token BIGINT NOT NULL DEFAULT 0'
    UNION ALL
    SELECT 11, 'column', 'lease_until', 'ADD COLUMN lease_until TIMESTAMP(3) NULL'
    UNION ALL
    SELECT 12, 'column', 'next_run_at', 'ADD COLUMN next_run_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)'
    UNION ALL
    SELECT 13, 'column', 'attempts', 'ADD COLUMN attempts INT NOT NULL DEFAULT 0'
    UNION ALL
    SELECT 14, 'index', 'ix_unified_jobs', 'ADD INDEX ix_unified_jobs(operation,status,next_run_at)'
    UNION ALL
    SELECT 15, 'index', 'ix_unified_doc', 'ADD INDEX ix_unified_doc(document_id,publish_seq)'
) AS required
WHERE (required.object_kind = 'column' AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS AS existing
    WHERE existing.TABLE_SCHEMA = DATABASE() AND existing.TABLE_NAME = 'kb_ingest_task'
      AND existing.COLUMN_NAME = required.object_name
)) OR (required.object_kind = 'index' AND NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS AS existing
    WHERE existing.TABLE_SCHEMA = DATABASE() AND existing.TABLE_NAME = 'kb_ingest_task'
      AND existing.INDEX_NAME = required.object_name
));
SET @kb_unified_sql = IF(@kb_unified_additions IS NULL, 'DO 0',
    CONCAT('ALTER TABLE kb_ingest_task ', @kb_unified_additions));
PREPARE kb_unified_statement FROM @kb_unified_sql;
EXECUTE kb_unified_statement;
DEALLOCATE PREPARE kb_unified_statement;

-- Add only the known V6 columns and indexes that are absent from kb_document.
SELECT GROUP_CONCAT(required.addition ORDER BY required.position SEPARATOR ', ')
INTO @kb_unified_additions
FROM (
    SELECT 1 AS position, 'column' AS object_kind, 'document_id' AS object_name, 'ADD COLUMN document_id CHAR(36) NULL' AS addition
    UNION ALL
    SELECT 2, 'column', 'release_id', 'ADD COLUMN release_id CHAR(36) NULL'
    UNION ALL
    SELECT 3, 'column', 'publish_seq', 'ADD COLUMN publish_seq BIGINT NULL'
    UNION ALL
    SELECT 4, 'column', 'source_rev_no', 'ADD COLUMN source_rev_no BIGINT NULL'
    UNION ALL
    SELECT 5, 'column', 'content_type', 'ADD COLUMN content_type VARCHAR(16) NULL'
    UNION ALL
    SELECT 6, 'column', 'object_key', 'ADD COLUMN object_key VARCHAR(512) NULL'
    UNION ALL
    SELECT 7, 'column', 'sha256', 'ADD COLUMN sha256 CHAR(64) NULL'
    UNION ALL
    SELECT 8, 'index', 'uq_release', 'ADD UNIQUE KEY uq_release(release_id)'
    UNION ALL
    SELECT 9, 'index', 'ix_unified_release', 'ADD INDEX ix_unified_release(document_id,publish_seq)'
) AS required
WHERE (required.object_kind = 'column' AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS AS existing
    WHERE existing.TABLE_SCHEMA = DATABASE() AND existing.TABLE_NAME = 'kb_document'
      AND existing.COLUMN_NAME = required.object_name
)) OR (required.object_kind = 'index' AND NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS AS existing
    WHERE existing.TABLE_SCHEMA = DATABASE() AND existing.TABLE_NAME = 'kb_document'
      AND existing.INDEX_NAME = required.object_name
));
SET @kb_unified_sql = IF(@kb_unified_additions IS NULL, 'DO 0',
    CONCAT('ALTER TABLE kb_document ', @kb_unified_additions));
PREPARE kb_unified_statement FROM @kb_unified_sql;
EXECUTE kb_unified_statement;
DEALLOCATE PREPARE kb_unified_statement;

SET SESSION group_concat_max_len = @kb_unified_old_group_concat_max_len;
SET @kb_unified_additions = NULL, @kb_unified_sql = NULL, @kb_unified_old_group_concat_max_len = NULL;
