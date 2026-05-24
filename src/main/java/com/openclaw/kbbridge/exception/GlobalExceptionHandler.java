package com.openclaw.kbbridge.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 全局异常处理器。
 * <p>
 * 统一捕获并处理所有自定义异常和 Spring 校验异常，
 * 返回一致的 {@link ErrorResponse} 格式。
 * 4xx 异常以 WARN 级别记录，5xx 异常以 ERROR 级别记录。
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理参数校验异常（自定义）。
     * HTTP 400 Bad Request。
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(ValidationException ex) {
        log.warn("参数校验失败 [requestId={}, field={}]: {}", ex.getRequestId(), ex.getField(), ex.getMessage());
        ErrorResponse body = buildResponse(
                ex.getRequestId(),
                HttpStatus.BAD_REQUEST,
                "ValidationException",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 处理 Spring @Valid/@NotBlank 等注解触发的校验异常。
     * HTTP 400 Bad Request。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String fieldError = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", fieldError);
        ErrorResponse body = buildResponse(
                "unknown",
                HttpStatus.BAD_REQUEST,
                "MethodArgumentNotValidException",
                fieldError);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        ErrorResponse body = buildResponse(
                "unknown",
                HttpStatus.BAD_REQUEST,
                "HttpMessageNotReadableException",
                "Malformed request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 处理入库内容重复异常。
     * HTTP 409 Conflict。
     */
    @ExceptionHandler(DuplicateContentException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateContentException(DuplicateContentException ex) {
        log.warn("内容重复 [requestId={}, existingTaskId={}]: {}",
                ex.getRequestId(), ex.getExistingTaskId(), ex.getMessage());
        ErrorResponse body = buildResponse(
                ex.getRequestId(),
                HttpStatus.CONFLICT,
                "DuplicateContentException",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 处理业务逻辑异常。
     * HTTP 500 Internal Server Error。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBizException(BizException ex) {
        log.error("业务异常 [requestId={}]: {}", ex.getRequestId(), ex.getMessage(), ex);
        ErrorResponse body = buildResponse(
                ex.getRequestId(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "BizException",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * 处理外部服务调用异常。
     * HTTP 502 Bad Gateway。
     * 日志中记录 serviceName，但响应体中不暴露（安全考虑）。
     */
    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ErrorResponse> handleExternalServiceException(ExternalServiceException ex) {
        log.error("外部服务调用失败 [requestId={}, serviceName={}, statusCode={}]: {}",
                ex.getRequestId(), ex.getServiceName(), ex.getStatusCode(), ex.getMessage(), ex);
        ErrorResponse body = buildResponse(
                ex.getRequestId(),
                HttpStatus.BAD_GATEWAY,
                "ExternalServiceException",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * 构建统一错误响应。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ErrorResponse body = buildResponse(
                "unknown",
                HttpStatus.INTERNAL_SERVER_ERROR,
                "InternalServerError",
                "Internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ErrorResponse buildResponse(String requestId, HttpStatus status, String error, String message) {
        return new ErrorResponse(
                requestId != null ? requestId : "unknown",
                status.value(),
                error,
                message,
                Instant.now().toString());
    }
}
