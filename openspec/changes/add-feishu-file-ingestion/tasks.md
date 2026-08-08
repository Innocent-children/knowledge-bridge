## 1. Persistent File Task Model

- [x] 1.1 Add database migration and model fields for raw object identity, file metadata, INDEXING state, external document/job IDs, and diagnostic status
- [x] 1.2 Add configuration for file size, supported formats, extraction, KBVector dataset, and vector-call timeout

## 2. File Intake

- [x] 2.1 Add the authenticated multipart `/api/v1/ingest/file` endpoint and request validation
- [x] 2.2 Stream uploaded content to MinIO with SHA-256 verification and deterministic collision-safe object keys
- [x] 2.3 Create or return an idempotent durable task and schedule the worker using only taskId

## 3. Extraction and Processing

- [x] 3.1 Implement text/Markdown/CSV/JSON, PDF, and DOCX extraction with empty-content failure handling
- [x] 3.2 Update the async worker to restore file input, run existing attachment processing and quality checks, and persist stage-specific failures

## 4. KBVector Publishing

- [x] 4.1 Replace the create DTO/response with KBVector `title + chunks + idempotency_key` contract and safe retry behavior
- [x] 4.2 Publish processed documents during INDEXING, persist document/job IDs, and complete only after all outputs succeed
- [x] 4.3 Align KBVector enable, disable, and permanent-delete client operations

## 5. Status and Verification

- [x] 5.1 Extend task status with file, stage, external ID, error code, and retryability fields
- [x] 5.2 Add controller, storage, extraction, worker, KBVector contract, idempotency, and failure-path tests
- [ ] 5.3 Run Maven unit/integration validation and verify existing text ingestion remains compatible
