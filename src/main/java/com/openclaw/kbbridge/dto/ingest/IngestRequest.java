package com.openclaw.kbbridge.dto.ingest;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 入库请求 DTO。
 *
 * @param requestId   请求唯一标识
 * @param userId      用户 ID
 * @param chatId      群聊 ID（可空）
 * @param messageIds  关联消息 ID 列表（可空）
 * @param content     原始内容
 * @param sourceType  来源类型（FEISHU_CHAT / MARKDOWN / ATTACHMENT）
 * @param attachments 附件列表（可空）
 * @param force       是否强制跳过去重
 */
public record IngestRequest(
                // 请求唯一标识，用于幂等
                @NotBlank(message = "requestId 不能为空") String requestId,
                // 发起方用户 ID
                @NotBlank(message = "userId 不能为空") String userId,
                // 群聊 ID，私聊可为 null
                String chatId,
                // 关联消息 ID 列表，可为 null
                List<String> messageIds,
                // 待入库的原始文本内容
                @NotBlank(message = "content 不能为空") String content,
                // 来源类型：FEISHU_CHAT / MARKDOWN / ATTACHMENT
                @NotBlank(message = "sourceType 不能为空") String sourceType,
                // 附件列表，可为 null
                List<Attachment> attachments,
                // 是否强制跳过内容去重（content-hash 重复时也入库）
                boolean force) {
}
