package com.openclaw.kbbridge.dto.ingest;

/**
 * 入库响应 DTO。
 *
 * @param requestId 请求唯一标识
 * @param taskId    入库任务 ID
 * @param status    入库状态
 * @param duplicate 是否内容重复
 */
public record IngestResponse(
                // 回显请求的 requestId
                String requestId,
                // 入库任务 ID（kb_ingest_task.id），后续状态查询和审核使用
                Long taskId,
                // 入库任务当前状态（DocumentStatus 枚举名）
                String status,
                // 是否检测到内容重复（force=false 且已存在同 hash 任务时为 true）
                boolean duplicate) {
}
