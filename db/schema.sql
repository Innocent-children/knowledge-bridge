CREATE DATABASE IF NOT EXISTS kb_bridge CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE kb_bridge;

-- Current processing schema. Original documents and their revisions belong to notes-blog.
CREATE TABLE IF NOT EXISTS processing_document (
  document_id CHAR(36) PRIMARY KEY,
  publish_seq BIGINT NOT NULL DEFAULT 0,
  desired_state ENUM('PUBLISH','WITHDRAWN','DELETED') NOT NULL DEFAULT 'PUBLISH',
  effective_release_id CHAR(36) NULL,
  cleanup_complete BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS processing_preview (
  rewrite_id CHAR(36) PRIMARY KEY,
  document_id CHAR(36) NOT NULL,
  source_rev_no BIGINT NOT NULL,
  source_sha256 CHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  output_json LONGTEXT NULL,
  input_json LONGTEXT NOT NULL,
  state ENUM('QUEUED','RUNNING','READY','FAILED','CANCELLED') NOT NULL DEFAULT 'QUEUED',
  stage VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
  guide_md LONGTEXT NULL,
  qa_md LONGTEXT NULL,
  partial_guide LONGTEXT NULL,
  partial_qa LONGTEXT NULL,
  attempts INT NOT NULL DEFAULT 0,
  lease_token CHAR(36) NULL,
  lease_until DATETIME(3) NULL,
  next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  error_code VARCHAR(80) NULL,
  error_message VARCHAR(500) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY ix_preview_document(document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS processing_release (
  release_id CHAR(36) PRIMARY KEY,
  document_id CHAR(36) NOT NULL,
  publish_seq BIGINT NOT NULL,
  source_rev_no BIGINT NOT NULL,
  content_type ENUM('SOURCE','GUIDE_QA') NOT NULL,
  request_hash CHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content LONGTEXT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  state ENUM('QUEUED','INDEXING','EFFECTIVE','SUPERSEDED','FAILED','WITHDRAWN') NOT NULL DEFAULT 'QUEUED',
  object_key VARCHAR(512) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  lease_token CHAR(36) NULL,
  lease_until DATETIME(3) NULL,
  next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  error_code VARCHAR(80) NULL,
  error_message VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_document_seq(document_id,publish_seq),
  KEY ix_release_due(state,next_run_at),
  FOREIGN KEY(document_id) REFERENCES processing_document(document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
