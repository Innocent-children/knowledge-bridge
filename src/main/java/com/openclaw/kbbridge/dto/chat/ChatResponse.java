package com.openclaw.kbbridge.dto.chat;

import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.model.enums.QueryRoute;

import java.util.List;

/**
 * 聊天响应 DTO。
 *
 * @param answer       LLM 生成的回答（LLM 失败时为 null）
 * @param route        本轮查询路由模式
 * @param sources      知识证据来源列表
 * @param llmError     LLM 调用是否失败
 * @param errorMessage 错误描述（成功时为 null）
 */
public record ChatResponse(
                // LLM 生成的最终回答；LLM 调用失败时为 null
                String answer,
                // 本轮查询路由模式（KB_ONLY / KB_PLUS_LLM / LLM_ONLY）
                QueryRoute route,
                // 支撑本次回答的知识证据来源列表
                List<EvidenceSource> sources,
                // LLM 调用是否失败；true 表示已降级返回
                boolean llmError,
                // 错误描述；成功时为 null
                String errorMessage) {
}
