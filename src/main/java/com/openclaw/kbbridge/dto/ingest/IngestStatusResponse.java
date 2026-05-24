package com.openclaw.kbbridge.dto.ingest;

import java.time.LocalDateTime;

/**
 * 入库状态查询响应 DTO。
 *
 * @param taskId            入库任务 ID
 * @param requestId         请求唯一标识
 * @param status            入库状态
 * @param reviewStatus      审核状态
 * @param rawObjectKey      MinIO 原始件路径
 * @param processedGuideKey Guide 处理件路径
 * @param processedQaKey    Q&A 处理件路径
 * @param errorMessage      错误信息
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 */
public record IngestStatusResponse(
                // 入库任务 ID
                Long taskId,
                // 原始请求的 requestId
                String requestId,
                // 入库状态（DocumentStatus 枚举名）
                String status,
                // 审核状态（ReviewStatus 枚举名）
                String reviewStatus,
                // MinIO 中原始件的对象键（bucket 内路径）
                String rawObjectKey,
                // MinIO 中 Guide 处理件的对象键
                String processedGuideKey,
                // MinIO 中 Q&A 处理件的对象键
                String processedQaKey,
                // 失败时的错误信息；成功时为 null
                String errorMessage,
                // 创建时间
                LocalDateTime createdAt,
                // 更新时间
                LocalDateTime updatedAt) {
}
