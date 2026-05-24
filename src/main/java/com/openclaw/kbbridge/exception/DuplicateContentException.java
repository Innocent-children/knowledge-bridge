package com.openclaw.kbbridge.exception;

/**
 * 入库内容重复异常。
 * <p>
 * 当入库内容的 content_hash 与已有成功任务匹配时抛出。
 * 对应 HTTP 状态码 409。
 * </p>
 */
public class DuplicateContentException extends RuntimeException {

    /**
     * 请求唯一标识，用于问题追踪（可空）
     */
    private final String requestId;

    /**
     * 已存在的入库任务 ID
     */
    private final Long existingTaskId;

    public DuplicateContentException(String message, String requestId, Long existingTaskId) {
        super(message);
        this.requestId = requestId;
        this.existingTaskId = existingTaskId;
    }

    public String getRequestId() {
        return requestId;
    }

    public Long getExistingTaskId() {
        return existingTaskId;
    }
}
