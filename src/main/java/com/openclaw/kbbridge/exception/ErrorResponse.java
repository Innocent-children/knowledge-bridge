package com.openclaw.kbbridge.exception;

/**
 * 统一错误响应结构。
 * <p>
 * 所有异常处理返回的标准化错误响应格式。
 * </p>
 *
 * @param requestId 请求唯一标识，用于问题追踪
 * @param code      HTTP 状态码
 * @param error     错误类型名称
 * @param message   人类可读的错误信息
 * @param timestamp ISO 8601 格式的时间戳
 */
public record ErrorResponse(
        String requestId,
        int code,
        String error,
        String message,
        String timestamp) {
}
