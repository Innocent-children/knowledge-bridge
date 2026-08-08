## ADDED Requirements

### Requirement: Accept an authenticated single-file ingestion request
Knowledge Bridge SHALL accept one multipart file with required source metadata, enforce the configured size and format allowlist, and identify a repeated request by requestId.

#### Scenario: Valid file is submitted
- **WHEN** an authenticated client uploads one supported file with complete metadata and a valid content hash
- **THEN** the service returns a taskId after durably storing the original object and task input

#### Scenario: RequestId is replayed
- **WHEN** the same requestId is submitted again
- **THEN** the service returns the existing logical task without creating another task or raw object

#### Scenario: File is invalid
- **WHEN** the file exceeds the configured limit, has an unsupported format, or fails hash validation
- **THEN** the service rejects the request without scheduling processing

### Requirement: Persist restart-safe file input
Knowledge Bridge SHALL persist the raw object key, file identity, size, MIME type, hash, and source message metadata before asynchronous processing begins.

#### Scenario: Worker starts after request completion
- **WHEN** the asynchronous worker receives a taskId
- **THEN** it loads all required input from the database and MinIO instead of relying on request-memory arguments

#### Scenario: Original object cannot be read
- **WHEN** the persisted MinIO object is missing or unreadable
- **THEN** the task becomes FAILED with a storage-stage error and is not sent to KBVector

### Requirement: Extract supported files into knowledge text
Knowledge Bridge SHALL extract supported text, PDF, and DOCX files into non-empty normalized text before invoking the existing attachment processing pipeline.

#### Scenario: Supported document extracts successfully
- **WHEN** a supported file yields non-empty text
- **THEN** the service passes normalized text and source metadata to the attachment processor and quality checks

#### Scenario: Extraction is empty or fails
- **WHEN** extraction fails or produces only whitespace
- **THEN** the task becomes FAILED with a safe extraction error and preserves the raw object for diagnosis

### Requirement: Expose file processing state
Knowledge Bridge SHALL expose the current file-processing stage and safe diagnostic fields through task status.

#### Scenario: Task is indexing
- **WHEN** processed chunks have been prepared and KBVector submission is in progress
- **THEN** task status reports INDEXING and does not report completion

#### Scenario: Task reaches a terminal failure
- **WHEN** storage, extraction, processing, or indexing fails
- **THEN** status reports FAILED with a stage-specific error code and retryability indicator
