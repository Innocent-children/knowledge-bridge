package com.openclaw.kbbridge.dto.review;

import java.time.LocalDateTime;

/**
 * 审核详情响应 DTO。
 *
 * @param taskId            入库任务 ID
 * @param requestId         请求唯一标识
 * @param userId            用户 ID
 * @param sourceType        来源类型
 * @param status            入库状态
 * @param reviewStatus      审核状态
 * @param rawObjectKey      MinIO 原始件路径
 * @param processedGuideKey Guide 处理件路径
 * @param processedQaKey    Q&A 处理件路径
 * @param contentPreview    内容摘要（前 200 字）
 * @param errorMessage      错误信息
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record ReviewDetailResponse(
                // 入库任务 ID
                Long taskId,
                // 原始请求的 requestId
                String requestId,
                // 提交人用户 ID
                String userId,
                // 来源类型（FEISHU_CHAT / MARKDOWN / ATTACHMENT）
                String sourceType,
                // 入库状态（DocumentStatus 枚举名）
                String status,
                // 审核状态（ReviewStatus 枚举名）
                String reviewStatus,
                // MinIO 中原始件的对象键
                String rawObjectKey,
                // MinIO 中 Guide 处理件的对象键
                String processedGuideKey,
                // MinIO 中 Q&A 处理件的对象键
                String processedQaKey,
                // 内容预览（前 200 字，便于审核页展示）
                String contentPreview,
                // 失败时的错误信息；成功时为 null
                String errorMessage,
                // 创建时间
                LocalDateTime createdAt,
                // 更新时间
                LocalDateTime updatedAt) {
}
