-- Test-only equivalent of V2-V6 after the single-administrator refactor.
-- MySQL ENGINE/charset/comments and index-only clauses are omitted; constraints and columns are retained.
CREATE TABLE kb_ingest_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id VARCHAR(64) NOT NULL UNIQUE,
 source_channel VARCHAR(32) NOT NULL, source_type VARCHAR(32) NOT NULL, user_id VARCHAR(64) NOT NULL,
 chat_id VARCHAR(64), message_ids_json CLOB, status VARCHAR(32) NOT NULL, review_status VARCHAR(32) NOT NULL,
 raw_object_key VARCHAR(512), processed_guide_key VARCHAR(512), processed_qa_key VARCHAR(512),
 content_hash VARCHAR(64) NOT NULL, processor_version VARCHAR(16), error_message CLOB,
 file_name VARCHAR(512), mime_type VARCHAR(255), file_size BIGINT, source_message_id VARCHAR(255),
 external_document_id VARCHAR(255), external_job_id VARCHAR(255), error_code VARCHAR(64), retryable BOOLEAN,
 document_id CHAR(36), release_id CHAR(36), operation VARCHAR(16), source_rev_no BIGINT, publish_seq BIGINT,
 payload_json CLOB, request_fingerprint CHAR(64), lease_owner VARCHAR(64), lease_token BIGINT NOT NULL DEFAULT 0,
 lease_until TIMESTAMP(3), next_run_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 retry_count INT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE kb_document (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, knowledge_type VARCHAR(16) NOT NULL,
 title VARCHAR(256) NOT NULL, topic VARCHAR(128), tags_json CLOB,
 review_status VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL,
 dataset_name VARCHAR(128), ragflow_document_id VARCHAR(128), metadata_json CLOB,
 version INT NOT NULL DEFAULT 1, document_id CHAR(36), release_id CHAR(36) UNIQUE,
 publish_seq BIGINT, source_rev_no BIGINT, content_type VARCHAR(16), object_key VARCHAR(512), sha256 CHAR(64),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE kb_review_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, review_status VARCHAR(32) NOT NULL,
 reviewer VARCHAR(64), `comment` CLOB, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE kb_logical_document (
 document_id CHAR(36) PRIMARY KEY, source_ref VARCHAR(256) NOT NULL, source VARCHAR(16) NOT NULL,
 source_rev_no BIGINT NOT NULL DEFAULT 0, desired_seq BIGINT NOT NULL DEFAULT 0,
 desired_state VARCHAR(16) NOT NULL DEFAULT 'DRAFT', effective_release_id CHAR(36),
 cleanup_complete BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(source,source_ref), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
