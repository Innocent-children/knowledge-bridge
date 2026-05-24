package com.openclaw.kbbridge.exception;

import net.jqwik.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalExceptionHandler 属性测试。
 * <p>
 * 使用 jqwik 属性测试框架验证异常处理映射的正确性。
 * 不依赖 Spring 上下文，直接实例化 GlobalExceptionHandler 进行测试。
 * </p>
 * <p>
 * Feature: knowledge-bridge, Property 21: 异常处理映射正确性
 * Validates: Requirements 24.1, 24.2, 24.3, 24.4, 24.5
 */
class GlobalExceptionHandlerPropertyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 验证 ValidationException 始终映射到 HTTP 400。
     * 对任意随机 message 和可空 requestId，响应状态码始终为 400，
     * 且响应体包含非空 requestId（null 时回退为 "unknown"）。
     * <p>
     * Feature: knowledge-bridge, Property 21: 异常处理映射正确性
     * **Validates: Requirements 24.2**
     */
    @Property(tries = 100)
    void validationException_alwaysMapsTo400(
            @ForAll String message,
            @ForAll("nullableStrings") String requestId) {

        var ex = new ValidationException(message, requestId);

        ResponseEntity<ErrorResponse> response = handler.handleValidationException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().code());
        assertEquals("ValidationException", response.getBody().error());
        assertNotNull(response.getBody().requestId());
        assertFalse(response.getBody().requestId().isEmpty());
    }

    /**
     * 验证 ExternalServiceException 始终映射到 HTTP 502。
     * 对任意随机 message、可空 requestId、随机 serviceName 和可空 statusCode，
     * 响应状态码始终为 502，且响应体包含非空 requestId。
     * <p>
     * Feature: knowledge-bridge, Property 21: 异常处理映射正确性
     * **Validates: Requirements 24.3**
     */
    @Property(tries = 100)
    void externalServiceException_alwaysMapsTo502(
            @ForAll String message,
            @ForAll("nullableStrings") String requestId,
            @ForAll String serviceName,
            @ForAll("nullableIntegers") Integer statusCode) {

        var ex = new ExternalServiceException(message, requestId, serviceName, statusCode);

        ResponseEntity<ErrorResponse> response = handler.handleExternalServiceException(ex);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(502, response.getBody().code());
        assertEquals("ExternalServiceException", response.getBody().error());
        assertNotNull(response.getBody().requestId());
        assertFalse(response.getBody().requestId().isEmpty());
    }

    /**
     * 验证 DuplicateContentException 始终映射到 HTTP 409。
     * 对任意随机 message 和可空 requestId，响应状态码始终为 409，
     * 且响应体包含非空 requestId。
     * <p>
     * Feature: knowledge-bridge, Property 21: 异常处理映射正确性
     * **Validates: Requirements 24.4**
     */
    @Property(tries = 100)
    void duplicateContentException_alwaysMapsTo409(
            @ForAll String message,
            @ForAll("nullableStrings") String requestId,
            @ForAll @From("nullableLongs") Long existingTaskId) {

        var ex = new DuplicateContentException(message, requestId, existingTaskId);

        ResponseEntity<ErrorResponse> response = handler.handleDuplicateContentException(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(409, response.getBody().code());
        assertEquals("DuplicateContentException", response.getBody().error());
        assertNotNull(response.getBody().requestId());
        assertFalse(response.getBody().requestId().isEmpty());
    }

    /**
     * 验证 BizException 始终映射到 HTTP 500。
     * 对任意随机 message 和可空 requestId，响应状态码始终为 500，
     * 且响应体包含非空 requestId。
     * <p>
     * Feature: knowledge-bridge, Property 21: 异常处理映射正确性
     * **Validates: Requirements 24.1**
     */
    @Property(tries = 100)
    void bizException_alwaysMapsTo500(
            @ForAll String message,
            @ForAll("nullableStrings") String requestId) {

        var ex = new BizException(message, requestId);

        ResponseEntity<ErrorResponse> response = handler.handleBizException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().code());
        assertEquals("BizException", response.getBody().error());
        assertNotNull(response.getBody().requestId());
        assertFalse(response.getBody().requestId().isEmpty());
    }

    /**
     * 验证所有异常类型的响应都包含非空 requestId。
     * 当异常中的 requestId 为 null 时，响应中应回退为 "unknown"。
     * 当异常中的 requestId 非 null 时，响应中应保留原始值。
     * <p>
     * Feature: knowledge-bridge, Property 21: 异常处理映射正确性
     * **Validates: Requirements 24.5**
     */
    @Property(tries = 100)
    void allResponses_alwaysContainNonNullRequestId(
            @ForAll String message,
            @ForAll("nullableStrings") String requestId) {

        // 测试所有四种异常类型
        var validationEx = new ValidationException(message, requestId);
        var bizEx = new BizException(message, requestId);
        var duplicateEx = new DuplicateContentException(message, requestId, 1L);
        var externalEx = new ExternalServiceException(message, requestId, "TestService", null);

        ErrorResponse validationBody = handler.handleValidationException(validationEx).getBody();
        ErrorResponse bizBody = handler.handleBizException(bizEx).getBody();
        ErrorResponse duplicateBody = handler.handleDuplicateContentException(duplicateEx).getBody();
        ErrorResponse externalBody = handler.handleExternalServiceException(externalEx).getBody();

        // 所有响应的 requestId 都不为 null
        assertNotNull(validationBody.requestId());
        assertNotNull(bizBody.requestId());
        assertNotNull(duplicateBody.requestId());
        assertNotNull(externalBody.requestId());

        // 当原始 requestId 为 null 时，应回退为 "unknown"
        if (requestId == null) {
            assertEquals("unknown", validationBody.requestId());
            assertEquals("unknown", bizBody.requestId());
            assertEquals("unknown", duplicateBody.requestId());
            assertEquals("unknown", externalBody.requestId());
        } else {
            assertEquals(requestId, validationBody.requestId());
            assertEquals(requestId, bizBody.requestId());
            assertEquals(requestId, duplicateBody.requestId());
            assertEquals(requestId, externalBody.requestId());
        }
    }

    /**
     * 验证所有异常类型的响应都包含非空的 ISO 8601 格式时间戳。
     * ISO 8601 格式的时间戳应包含 'T' 分隔符。
     * <p>
     * Feature: knowledge-bridge, Property 21: 异常处理映射正确性
     * **Validates: Requirements 24.5**
     */
    @Property(tries = 100)
    void allResponses_alwaysContainIso8601Timestamp(
            @ForAll String message,
            @ForAll("nullableStrings") String requestId) {

        var validationEx = new ValidationException(message, requestId);
        var bizEx = new BizException(message, requestId);
        var duplicateEx = new DuplicateContentException(message, requestId, 1L);
        var externalEx = new ExternalServiceException(message, requestId, "TestService", null);

        ErrorResponse validationBody = handler.handleValidationException(validationEx).getBody();
        ErrorResponse bizBody = handler.handleBizException(bizEx).getBody();
        ErrorResponse duplicateBody = handler.handleDuplicateContentException(duplicateEx).getBody();
        ErrorResponse externalBody = handler.handleExternalServiceException(externalEx).getBody();

        // 所有响应的 timestamp 都不为 null 且为 ISO 8601 格式
        assertTimestampIsIso8601(validationBody.timestamp());
        assertTimestampIsIso8601(bizBody.timestamp());
        assertTimestampIsIso8601(duplicateBody.timestamp());
        assertTimestampIsIso8601(externalBody.timestamp());
    }

    // ========== 辅助方法 ==========

    /**
     * 断言时间戳为 ISO 8601 格式（包含 'T' 分隔符且可被 Instant 解析）。
     */
    private void assertTimestampIsIso8601(String timestamp) {
        assertNotNull(timestamp, "timestamp 不应为 null");
        assertTrue(timestamp.contains("T"), "timestamp 应为 ISO 8601 格式，包含 'T' 分隔符");
        assertDoesNotThrow(() -> java.time.Instant.parse(timestamp),
                "timestamp 应可被 Instant.parse 解析为有效的 ISO 8601 时间");
    }

    // ========== 自定义 Arbitrary 提供者 ==========

    /**
     * 提供可空字符串的 Arbitrary，用于测试 requestId 为 null 和非 null 的情况。
     */
    @Provide
    Arbitrary<String> nullableStrings() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(64)
                .injectNull(0.3);
    }

    /**
     * 提供可空 Integer 的 Arbitrary，用于测试 ExternalServiceException 的 statusCode。
     */
    @Provide
    Arbitrary<Integer> nullableIntegers() {
        return Arbitraries.integers().between(100, 599)
                .injectNull(0.3);
    }

    /**
     * 提供可空 Long 的 Arbitrary，用于测试 DuplicateContentException 的 existingTaskId。
     */
    @Provide
    Arbitrary<Long> nullableLongs() {
        return Arbitraries.longs().between(1L, 100000L)
                .injectNull(0.3);
    }
}
