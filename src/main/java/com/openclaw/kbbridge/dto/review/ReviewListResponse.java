package com.openclaw.kbbridge.dto.review;

import java.time.LocalDateTime;

/**
 * 待审核列表响应 DTO。
 *
 * @param taskId         入库任务 ID
 * @param requestId      请求唯一标识
 * @param userId         用户 ID
 * @param sourceType     来源类型
 * @param contentPreview 内容摘要（前 200 字）
 * @param reviewStatus   审核状态
 * @param createdAt      创建时间
 */
public record ReviewListResponse(
                // 入库任务 ID
                Long taskId,
                // 原始请求的 requestId
                String requestId,
                // 提交人用户 ID
                String userId,
                // 来源类型（FEISHU_CHAT / MARKDOWN / ATTACHMENT）
                String sourceType,
                // 内容预览（前 200 字，便于列表展示）
                String contentPreview,
                // 审核状态（ReviewStatus 枚举名）
                String reviewStatus,
                // 创建时间
                LocalDateTime createdAt,
                String status,
                String operation) {
    public ReviewListResponse(Long taskId, String requestId, String userId, String sourceType,
            String contentPreview, String reviewStatus, LocalDateTime createdAt) {
        this(taskId, requestId, userId, sourceType, contentPreview, reviewStatus, createdAt, null, null);
    }
}
