package com.openclaw.kbbridge.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalExceptionHandler 单元测试。
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleValidationException_returns400WithRequestId() {
        var ex = new ValidationException("字段格式错误", "req-001", "email");

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("req-001", body.requestId());
        assertEquals(400, body.code());
        assertEquals("ValidationException", body.error());
        assertEquals("字段格式错误", body.message());
        assertNotNull(body.timestamp());
    }

    @Test
    void handleValidationException_nullRequestId_returnsUnknown() {
        var ex = new ValidationException("缺少必填字段");

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("unknown", response.getBody().requestId());
    }

    @Test
    void handleDuplicateContentException_returns409() {
        var ex = new DuplicateContentException("内容已存在", "req-002", 42L);

        ResponseEntity<ErrorResponse> response = handler.handleDuplicateContentException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("req-002", body.requestId());
        assertEquals(409, body.code());
        assertEquals("DuplicateContentException", body.error());
    }

    @Test
    void handleBizException_returns500() {
        var ex = new BizException("状态机非法转换", "req-003");

        ResponseEntity<ErrorResponse> response = handler.handleBizException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("req-003", body.requestId());
        assertEquals(500, body.code());
        assertEquals("BizException", body.error());
    }

    @Test
    void handleExternalServiceException_returns502() {
        var ex = new ExternalServiceException("RAGFlow 超时", "req-004", "RAGFlow", 504);

        ResponseEntity<ErrorResponse> response = handler.handleExternalServiceException(ex);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("req-004", body.requestId());
        assertEquals(502, body.code());
        assertEquals("ExternalServiceException", body.error());
        // serviceName 不应出现在响应体中（安全考虑）
        assertFalse(body.message().contains("serviceName"));
    }

    @Test
    void handleExternalServiceException_nullRequestId_returnsUnknown() {
        var ex = new ExternalServiceException("连接失败", null, "MinIO", null);

        ResponseEntity<ErrorResponse> response = handler.handleExternalServiceException(ex);

        assertEquals("unknown", response.getBody().requestId());
    }

    @Test
    void allResponses_containTimestampInIso8601() {
        var ex = new BizException("test", "req-005");

        ResponseEntity<ErrorResponse> response = handler.handleBizException(ex);

        String timestamp = response.getBody().timestamp();
        assertNotNull(timestamp);
        // ISO 8601 格式包含 'T' 分隔符
        assertTrue(timestamp.contains("T"), "timestamp 应为 ISO 8601 格式");
    }
}
