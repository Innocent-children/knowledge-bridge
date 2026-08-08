package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.dto.ingest.FileIngestResponse;
import com.openclaw.kbbridge.service.FileIngestService;
import com.openclaw.kbbridge.config.KbProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ingest")
public class FileIngestController {
    private final FileIngestService service;
    private final KbProperties properties;

    public FileIngestController(FileIngestService service, KbProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping(value = "/file", consumes = "multipart/form-data")
    public ResponseEntity<FileIngestResponse> ingest(
            @RequestParam String requestId,
            @RequestParam String userId,
            @RequestParam(required = false) String chatId,
            @RequestParam String messageId,
            @RequestParam(required = false) String sha256,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestHeader("X-KB-File-Token") String token,
            @RequestParam MultipartFile file) {
        if (properties.getSecurity().getSharedSecret() == null
                || !java.security.MessageDigest.isEqual(
                properties.getSecurity().getSharedSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(service.ingest(requestId, userId, chatId, messageId,
                sha256, force, file));
    }
}
