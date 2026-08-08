package com.openclaw.kbbridge.dto.ingest;

public record FileIngestResponse(String requestId, Long taskId, String status, boolean duplicate) {
}
