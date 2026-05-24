package com.openclaw.kbbridge.preservation;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.*;
import com.openclaw.kbbridge.security.HmacSignatureFilter;
import com.openclaw.kbbridge.security.SignatureValidator;
import net.jqwik.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preservation Property Tests for Exception Handling & Security (Phase 2).
 * <p>
 * These tests verify existing correct behavior on UNFIXED code that must be
 * preserved after bug fixes are applied. They are EXPECTED TO PASS on unfixed
 * code.
 * </p>
 * <p>
 * Tests follow observation-first methodology: observe behavior on unfixed code
 * for non-buggy inputs (known exception types, valid HMAC signatures).
 * </p>
 */
class ExceptionSecurityPreservationPropertyTest {

    private static final String SHARED_SECRET = "test-preservation-secret-key-2024";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── Preservation P9: ValidationException → HTTP 400 with JSON ErrorResponse ──

    /**
     * Preservation P9: For any ValidationException, GlobalExceptionHandler returns
     * HTTP 400 with JSON ErrorResponse containing the correct error type and status
     * code.
     * <p>
     * This verifies the existing ValidationException handling is preserved after
     * fixes.
     * </p>
     *
     * <b>Validates: Requirements 3.6</b>
     */
    @Property(tries = 100)
    void validationExceptionReturnsHttp400WithJsonErrorResponse(
            @ForAll("errorMessages") String message,
            @ForAll("nullableRequestIds") String requestId,
            @ForAll("nullableFieldNames") String field) {

        ValidationException ex = new ValidationException(message, requestId, field);

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        // HTTP 400
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "ValidationException should map to HTTP 400");

        // Body is a valid ErrorResponse
        ErrorResponse body = response.getBody();
        assertNotNull(body, "Response body should not be null");
        assertEquals(400, body.code(), "ErrorResponse code should be 400");
        assertEquals("ValidationException", body.error(),
                "ErrorResponse error type should be 'ValidationException'");
        assertEquals(message, body.message(), "ErrorResponse message should match exception message");

        // requestId fallback to "unknown" when null
        assertNotNull(body.requestId(), "requestId should never be null in response");
        if (requestId == null) {
            assertEquals("unknown", body.requestId(),
                    "Null requestId should fall back to 'unknown'");
        } else {
            assertEquals(requestId, body.requestId(),
                    "Non-null requestId should be preserved");
        }

        // Timestamp is ISO 8601
        assertNotNull(body.timestamp(), "Timestamp should not be null");
        assertTrue(body.timestamp().contains("T"), "Timestamp should be ISO 8601 format");
    }

    // ── Preservation P10: MethodArgumentNotValidException → HTTP 400 ──

    /**
     * Preservation P10: For any MethodArgumentNotValidException,
     * GlobalExceptionHandler
     * returns HTTP 400 with JSON ErrorResponse.
     * <p>
     * This verifies the existing Spring validation exception handling is preserved.
     * </p>
     *
     * <b>Validates: Requirements 3.6</b>
     */
    @Property(tries = 50)
    void methodArgumentNotValidExceptionReturnsHttp400(
            @ForAll("fieldNames") String fieldName,
            @ForAll("errorMessages") String defaultMessage) {

        // Build a MethodArgumentNotValidException with a field error
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(
                new Object(), "testObject");
        bindingResult.addError(new FieldError(
                "testObject", fieldName, defaultMessage));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValid(ex);

        // HTTP 400
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                "MethodArgumentNotValidException should map to HTTP 400");

        // Body is a valid ErrorResponse
        ErrorResponse body = response.getBody();
        assertNotNull(body, "Response body should not be null");
        assertEquals(400, body.code(), "ErrorResponse code should be 400");
        assertEquals("MethodArgumentNotValidException", body.error(),
                "ErrorResponse error type should be 'MethodArgumentNotValidException'");

        // Message should contain the field name and default message
        assertNotNull(body.message(), "ErrorResponse message should not be null");
        assertTrue(body.message().contains(fieldName),
                "ErrorResponse message should contain the field name: " + fieldName);

        // requestId defaults to "unknown"
        assertEquals("unknown", body.requestId(),
                "MethodArgumentNotValidException requestId should be 'unknown'");

        // Timestamp is ISO 8601
        assertNotNull(body.timestamp(), "Timestamp should not be null");
        assertTrue(body.timestamp().contains("T"), "Timestamp should be ISO 8601 format");
    }

    // ── Preservation P11: DuplicateContentException → HTTP 409 ──

    /**
     * Preservation P11: For any DuplicateContentException, GlobalExceptionHandler
     * returns HTTP 409 with JSON ErrorResponse.
     * <p>
     * This verifies the existing duplicate content exception handling is preserved.
     * </p>
     *
     * <b>Validates: Requirements 3.6</b>
     */
    @Property(tries = 100)
    void duplicateContentExceptionReturnsHttp409(
            @ForAll("errorMessages") String message,
            @ForAll("nullableRequestIds") String requestId,
            @ForAll("taskIds") Long existingTaskId) {

        DuplicateContentException ex = new DuplicateContentException(message, requestId, existingTaskId);

        ResponseEntity<ErrorResponse> response = handler.handleDuplicateContentException(ex);

        // HTTP 409
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                "DuplicateContentException should map to HTTP 409");

        // Body is a valid ErrorResponse
        ErrorResponse body = response.getBody();
        assertNotNull(body, "Response body should not be null");
        assertEquals(409, body.code(), "ErrorResponse code should be 409");
        assertEquals("DuplicateContentException", body.error(),
                "ErrorResponse error type should be 'DuplicateContentException'");
        assertEquals(message, body.message(), "ErrorResponse message should match exception message");

        // requestId fallback
        assertNotNull(body.requestId(), "requestId should never be null in response");
        if (requestId == null) {
            assertEquals("unknown", body.requestId());
        } else {
            assertEquals(requestId, body.requestId());
        }

        // Timestamp is ISO 8601
        assertNotNull(body.timestamp());
        assertTrue(body.timestamp().contains("T"));
    }

    // ── Preservation P12: BizException → HTTP 500 ──

    /**
     * Preservation P12: For any BizException, GlobalExceptionHandler returns
     * HTTP 500 with JSON ErrorResponse.
     * <p>
     * This verifies the existing business exception handling is preserved.
     * </p>
     *
     * <b>Validates: Requirements 3.6</b>
     */
    @Property(tries = 100)
    void bizExceptionReturnsHttp500(
            @ForAll("errorMessages") String message,
            @ForAll("nullableRequestIds") String requestId) {

        BizException ex = new BizException(message, requestId);

        ResponseEntity<ErrorResponse> response = handler.handleBizException(ex);

        // HTTP 500
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "BizException should map to HTTP 500");

        // Body is a valid ErrorResponse
        ErrorResponse body = response.getBody();
        assertNotNull(body, "Response body should not be null");
        assertEquals(500, body.code(), "ErrorResponse code should be 500");
        assertEquals("BizException", body.error(),
                "ErrorResponse error type should be 'BizException'");
        assertEquals(message, body.message(), "ErrorResponse message should match exception message");

        // requestId fallback
        assertNotNull(body.requestId());
        if (requestId == null) {
            assertEquals("unknown", body.requestId());
        } else {
            assertEquals(requestId, body.requestId());
        }

        // Timestamp is ISO 8601
        assertNotNull(body.timestamp());
        assertTrue(body.timestamp().contains("T"));
    }

    // ── Preservation P13: ExternalServiceException → HTTP 502 ──

    /**
     * Preservation P13: For any ExternalServiceException, GlobalExceptionHandler
     * returns HTTP 502 with JSON ErrorResponse.
     * <p>
     * This verifies the existing external service exception handling is preserved.
     * </p>
     *
     * <b>Validates: Requirements 3.6</b>
     */
    @Property(tries = 100)
    void externalServiceExceptionReturnsHttp502(
            @ForAll("errorMessages") String message,
            @ForAll("nullableRequestIds") String requestId,
            @ForAll("serviceNames") String serviceName,
            @ForAll("nullableStatusCodes") Integer statusCode) {

        ExternalServiceException ex = new ExternalServiceException(
                message, requestId, serviceName, statusCode);

        ResponseEntity<ErrorResponse> response = handler.handleExternalServiceException(ex);

        // HTTP 502
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode(),
                "ExternalServiceException should map to HTTP 502");

        // Body is a valid ErrorResponse
        ErrorResponse body = response.getBody();
        assertNotNull(body, "Response body should not be null");
        assertEquals(502, body.code(), "ErrorResponse code should be 502");
        assertEquals("ExternalServiceException", body.error(),
                "ErrorResponse error type should be 'ExternalServiceException'");
        assertEquals(message, body.message(), "ErrorResponse message should match exception message");

        // requestId fallback
        assertNotNull(body.requestId());
        if (requestId == null) {
            assertEquals("unknown", body.requestId());
        } else {
            assertEquals(requestId, body.requestId());
        }

        // Timestamp is ISO 8601
        assertNotNull(body.timestamp());
        assertTrue(body.timestamp().contains("T"));
    }

    // ── Preservation P14: Valid HMAC signature passes through to controller ──

    /**
     * Preservation P14: For any request with valid HMAC signature, valid timestamp,
     * and valid headers, HmacSignatureFilter passes the request through to the
     * controller.
     * <p>
     * This verifies the existing HMAC authentication pass-through is preserved.
     * </p>
     *
     * <b>Validates: Requirements 3.7</b>
     */
    @Property(tries = 50)
    void validHmacSignaturePassesThroughToController(
            @ForAll("requestIds") String requestId,
            @ForAll("requestBodies") String requestBody,
            @ForAll("apiPaths") String apiPath) throws Exception {

        // Setup real SignatureValidator with known secret
        KbProperties props = new KbProperties();
        props.getSecurity().setSharedSecret(SHARED_SECRET);
        props.getSecurity().setTimestampToleranceMs(300000);
        props.getSecurity().setSignatureAlgorithm("HmacSHA256");
        SignatureValidator signatureValidator = new SignatureValidator(props);

        ObjectMapper objectMapper = new ObjectMapper();
        HmacSignatureFilter filter = new HmacSignatureFilter(signatureValidator, objectMapper);

        // Generate valid timestamp (current time)
        String timestamp = String.valueOf(System.currentTimeMillis());

        // Compute valid signature
        String signature = signatureValidator.sign(requestId, timestamp, requestBody);

        // Build request with valid headers
        MockHttpServletRequest request = new MockHttpServletRequest("POST", apiPath);
        request.setContent(requestBody.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.setCharacterEncoding("UTF-8");
        request.addHeader("X-KB-Signature", signature);
        request.addHeader("X-KB-Timestamp", timestamp);
        request.addHeader("X-KB-RequestId", requestId);

        MockHttpServletResponse response = new MockHttpServletResponse();

        // Use MockFilterChain to detect if the request was passed through
        MockFilterChain filterChain = new MockFilterChain();

        // Execute filter via public doFilter method
        filter.doFilter(request, response, filterChain);

        // If the filter passed the request through, MockFilterChain.getRequest() is
        // non-null
        assertNotNull(filterChain.getRequest(),
                "FilterChain should have been invoked (request passed through)");

        // Response status should remain 200 (not set to 401)
        assertEquals(200, response.getStatus(),
                "Response status should be 200 when signature is valid");
    }

    // ========== Custom Arbitrary Providers ==========

    @Provide
    Arbitrary<String> errorMessages() {
        return Arbitraries.of(
                "参数校验失败",
                "字段不能为空",
                "Invalid input",
                "内容重复",
                "业务逻辑异常",
                "外部服务调用失败",
                "请求超时",
                "数据格式错误",
                "权限不足",
                "资源不存在");
    }

    @Provide
    Arbitrary<String> nullableRequestIds() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20)
                .map(s -> "req-" + s)
                .injectNull(0.3);
    }

    @Provide
    Arbitrary<String> requestIds() {
        return Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20)
                .map(s -> "req-" + s);
    }

    @Provide
    Arbitrary<String> nullableFieldNames() {
        return Arbitraries.of("content", "requestId", "sourceType", "question", "userId")
                .injectNull(0.3);
    }

    @Provide
    Arbitrary<String> fieldNames() {
        return Arbitraries.of("content", "requestId", "sourceType", "question", "userId",
                "title", "category", "tags", "description");
    }

    @Provide
    Arbitrary<Long> taskIds() {
        return Arbitraries.longs().between(1L, 100000L);
    }

    @Provide
    Arbitrary<String> serviceNames() {
        return Arbitraries.of("RAGFlow", "LLM", "MinIO", "OpenClaw", "ExternalAPI");
    }

    @Provide
    Arbitrary<Integer> nullableStatusCodes() {
        return Arbitraries.integers().between(400, 599)
                .injectNull(0.3);
    }

    @Provide
    Arbitrary<String> requestBodies() {
        return Arbitraries.of(
                "{\"question\":\"hello\"}",
                "{\"content\":\"test content\"}",
                "",
                "{\"sourceType\":\"MARKDOWN\",\"content\":\"# Title\"}",
                "{\"requestId\":\"req-123\",\"question\":\"如何使用？\"}",
                "{\"data\":[1,2,3]}");
    }

    @Provide
    Arbitrary<String> apiPaths() {
        return Arbitraries.of(
                "/api/v1/query",
                "/api/v1/ingest/manual",
                "/api/v1/documents",
                "/api/v1/review/list",
                "/api/v1/ingest/status");
    }
}
