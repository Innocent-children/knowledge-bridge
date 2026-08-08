ALTER TABLE kb_ingest_task ADD COLUMN file_name VARCHAR(512) NULL;
ALTER TABLE kb_ingest_task ADD COLUMN mime_type VARCHAR(255) NULL;
ALTER TABLE kb_ingest_task ADD COLUMN file_size BIGINT NULL;
ALTER TABLE kb_ingest_task ADD COLUMN source_message_id VARCHAR(255) NULL;
ALTER TABLE kb_ingest_task ADD COLUMN external_document_id VARCHAR(255) NULL;
ALTER TABLE kb_ingest_task ADD COLUMN external_job_id VARCHAR(255) NULL;
ALTER TABLE kb_ingest_task ADD COLUMN error_code VARCHAR(64) NULL;
ALTER TABLE kb_ingest_task ADD COLUMN retryable BOOLEAN NULL;
