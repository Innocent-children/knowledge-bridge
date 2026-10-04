-- Apply once to the bridge database only. No historical object or row migration.
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
ALTER TABLE kb_ingest_task
  ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
  ADD COLUMN document_id CHAR(36) NULL,
  ADD COLUMN release_id CHAR(36) NULL,
  ADD COLUMN operation VARCHAR(16) NULL,
  ADD COLUMN source_rev_no BIGINT NULL,
  ADD COLUMN publish_seq BIGINT NULL,
  ADD COLUMN payload_json LONGTEXT NULL,
  ADD COLUMN request_fingerprint CHAR(64) NULL,
  ADD COLUMN lease_owner VARCHAR(64) NULL,
  ADD COLUMN lease_token BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN lease_until TIMESTAMP(3) NULL,
  ADD COLUMN next_run_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN attempts INT NOT NULL DEFAULT 0,
  ADD INDEX ix_unified_jobs(operation,status,next_run_at),
  ADD INDEX ix_unified_doc(document_id,publish_seq);
ALTER TABLE kb_document
  ADD COLUMN document_id CHAR(36) NULL,
  ADD COLUMN release_id CHAR(36) NULL,
  ADD COLUMN publish_seq BIGINT NULL,
  ADD COLUMN source_rev_no BIGINT NULL,
  ADD COLUMN content_type VARCHAR(16) NULL,
  ADD COLUMN object_key VARCHAR(512) NULL,
  ADD COLUMN sha256 CHAR(64) NULL,
  ADD UNIQUE KEY uq_release(release_id),
  ADD INDEX ix_unified_release(document_id,publish_seq);
