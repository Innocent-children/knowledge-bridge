package com.openclaw.kbbridge.dto.ingest;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 自动候选评估请求 DTO。
 * <p>
 * OpenClaw 转发普通消息时调用此接口，由 Knowledge Bridge 判定内容是否值得沉淀。
 * </p>
 *
 * @param requestId   请求唯一标识
 * @param userId      用户 ID
 * @param chatId      群聊 ID（可空）
 * @param messageIds  关联消息 ID 列表（可空）
 * @param content     消息内容
 * @param sourceType  来源类型（FEISHU_CHAT / MARKDOWN 等）
 * @param attachments 附件列表（可空）
 */
public record CandidateEvalRequest(
                // 请求唯一标识，用于幂等
                @NotBlank(message = "requestId 不能为空") String requestId,
                // 发起方用户 ID
                @NotBlank(message = "userId 不能为空") String userId,
                // 群聊 ID，私聊可为 null
                String chatId,
                // 关联的消息 ID 列表（如飞书消息合并评估），可为 null
                List<String> messageIds,
                // 待评估的原始内容
                @NotBlank(message = "content 不能为空") String content,
                // 来源类型：FEISHU_CHAT / MARKDOWN 等
                @NotBlank(message = "sourceType 不能为空") String sourceType,
                // 附件列表，可为 null
                List<Attachment> attachments) {
}
