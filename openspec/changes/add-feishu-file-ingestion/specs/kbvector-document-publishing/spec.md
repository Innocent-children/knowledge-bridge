## ADDED Requirements

### Requirement: Publish processed chunks using the KBVector contract
Knowledge Bridge SHALL publish each processed knowledge document through the KBVector JSON document API using a title, non-empty chunks, per-chunk metadata, and a stable idempotency key.

#### Scenario: Vector publication succeeds
- **WHEN** all processed chunks are accepted and indexed by KBVector
- **THEN** Knowledge Bridge stores the returned documentId and jobId and completes the corresponding document result

#### Scenario: Vector publication fails
- **WHEN** KBVector rejects or fails the indexing request
- **THEN** Knowledge Bridge keeps the task non-complete and records an indexing-stage failure

### Requirement: Complete only after all vector documents succeed
Knowledge Bridge SHALL mark the ingest task COMPLETED only after every expected processed document has a successful KBVector result.

#### Scenario: One of multiple processed outputs fails
- **WHEN** one expected knowledge output succeeds and another fails
- **THEN** the overall task becomes FAILED and retains the successful external identifiers for recovery

### Requirement: Use safe create retry semantics
Knowledge Bridge MUST NOT blindly retry KBVector document creation without a stable idempotency key and SHALL reuse exactly the same key for recovery of an ambiguous timeout.

#### Scenario: KBVector response times out after possible success
- **WHEN** the create response is lost or times out
- **THEN** Knowledge Bridge records an ambiguous indexing failure or replays only with the original idempotency key

### Requirement: Align document lifecycle operations
Knowledge Bridge SHALL call KBVector's enable and disable endpoints for reversible state changes and SHALL reserve DELETE for explicit permanent deletion.

#### Scenario: Existing external document is disabled
- **WHEN** a user disables a Knowledge Bridge document with an external documentId
- **THEN** the bridge calls the KBVector disable endpoint and does not physically delete the document
