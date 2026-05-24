package com.openclaw.kbbridge.dto.ingest;

/**
 * 自动候选评估响应 DTO。
 *
 * @param requestId 请求唯一标识
 * @param worthy    是否值得沉淀
 * @param reason    判定原因
 * @param taskId    若自动创建了候选任务，返回 taskId（worthy=true 时非空）
 * @param duplicate 是否内容重复
 */
public record CandidateEvalResponse(
                // 回显请求的 requestId
                String requestId,
                // 评估结果：true=值得沉淀，false=无价值内容
                boolean worthy,
                // 判定原因/说明（便于调用方展示或排查）
                String reason,
                // worthy=true 时自动创建的入库任务 ID，否则为 null
                Long taskId,
                // 是否因内容重复而跳过沉淀
                boolean duplicate) {
}
