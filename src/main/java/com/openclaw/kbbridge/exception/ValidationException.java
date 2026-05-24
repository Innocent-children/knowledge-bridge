package com.openclaw.kbbridge.exception;

/**
 * 请求参数校验异常。
 * <p>
 * 当请求参数缺少必填字段或格式错误时抛出。
 * 对应 HTTP 状态码 400。
 * </p>
 */
public class ValidationException extends RuntimeException {

    /**
     * 请求唯一标识，用于问题追踪（可空）
     */
    private final String requestId;

    /**
     * 校验失败的字段名称（可空）
     */
    private final String field;

    public ValidationException(String message, String requestId, String field) {
        super(message);
        this.requestId = requestId;
        this.field = field;
    }

    public ValidationException(String message, String requestId) {
        this(message, requestId, null);
    }

    public ValidationException(String message) {
        this(message, null, null);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getField() {
        return field;
    }
}
