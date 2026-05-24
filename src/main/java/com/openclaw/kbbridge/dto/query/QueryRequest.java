package com.openclaw.kbbridge.dto.query;

import jakarta.validation.constraints.NotBlank;

/**
 * 查询请求 DTO。
 *
 * @param requestId   请求唯一标识，用于幂等和追踪
 * @param userId      用户 ID
 * @param chatId      群聊 ID（可空）
 * @param sessionKey  会话标识（可空）
 * @param messageId   消息 ID（可空）
 * @param question    用户问题
 * @param channelType 渠道类型，如 feishu（可空）
 * @param isGroup     是否群聊
 * @param flags       查询控制标志（可空）
 */
public record QueryRequest(
                // 请求唯一标识，用于幂等和链路追踪，同一 requestId 不重复处理
                @NotBlank(message = "requestId 不能为空") String requestId,
                // 发起查询的用户 ID（业务方用户体系中的标识）
                @NotBlank(message = "userId 不能为空") String userId,
                // 群聊 ID，用于区分不同群会话；私聊可为 null
                String chatId,
                // 会话标识，用于关联同一会话内的多轮上下文（如 RAGFlow session）；可为 null
                String sessionKey,
                // 来源消息 ID（如飞书 message_id），用于回写或引用原消息；可为 null
                String messageId,
                // 用户真实的提问内容，不可为空
                @NotBlank(message = "question 不能为空") String question,
                // 渠道类型，标识请求来源，如 feishu、web；可为 null
                String channelType,
                // 是否群聊场景，true=群聊，false=私聊；影响回复策略和会话隔离
                boolean isGroup,
                // 查询控制标志（strictKbOnly、needCitation 等）；可为 null，走全局默认
                QueryFlags flags) {
}
