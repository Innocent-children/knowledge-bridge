package com.openclaw.kbbridge.exception;

/**
 * 业务逻辑异常。
 * <p>
 * 当业务逻辑出现非法状态时抛出，例如状态机非法转换等场景。
 * 对应 HTTP 状态码 500。
 * </p>
 */
public class BizException extends RuntimeException {

    /**
     * 请求唯一标识，用于问题追踪（可空）
     */
    private final String requestId;

    public BizException(String message, String requestId) {
        super(message);
        this.requestId = requestId;
    }

    public BizException(String message) {
        this(message, null);
    }

    public String getRequestId() {
        return requestId;
    }
}
