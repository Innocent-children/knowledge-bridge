package com.openclaw.kbbridge.exception;

/**
 * 外部服务调用异常。
 * <p>
 * 当调用 RAGFlow、LLM、MinIO 等外部服务失败时抛出。
 * 对应 HTTP 状态码 502。
 * </p>
 */
public class ExternalServiceException extends RuntimeException {

    /**
     * 请求唯一标识，用于问题追踪（可空）
     */
    private final String requestId;

    /**
     * 外部服务名称，例如 "RAGFlow"、"LLM"、"MinIO"
     */
    private final String serviceName;

    /**
     * 外部服务返回的 HTTP 状态码（可空）
     */
    private final Integer statusCode;

    public ExternalServiceException(String message, String requestId, String serviceName, Integer statusCode) {
        super(message);
        this.requestId = requestId;
        this.serviceName = serviceName;
        this.statusCode = statusCode;
    }

    public ExternalServiceException(String message, String requestId, String serviceName, Integer statusCode,
                                    Throwable cause) {
        super(message, cause);
        this.requestId = requestId;
        this.serviceName = serviceName;
        this.statusCode = statusCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
