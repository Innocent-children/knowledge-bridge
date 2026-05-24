package com.openclaw.kbbridge.dto.review;

import java.util.List;

/**
 * 批量审核响应 DTO。
 *
 * @param results 批量审核结果列表
 */
public record ReviewBatchResponse(
        // 批量审核的每一项结果，顺序对应请求中的 items
        List<BatchResult> results) {

    /**
     * 单条批量审核结果。
     *
     * @param taskId  入库任务 ID
     * @param success 是否成功
     * @param message 结果消息
     */
    public record BatchResult(
            // 对应的入库任务 ID
            Long taskId,
            // 该项是否审核成功
            boolean success,
            // 结果消息（成功时可为空，失败时为错误原因）
            String message) {
    }
}
