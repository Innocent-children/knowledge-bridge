package com.openclaw.kbbridge.dto.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求 DTO。
 *
 * @param question 用户问题文本
 */
public record ChatRequest(
                // 用户问题文本，不可为空
                @NotBlank(message = "question 不能为空") String question) {
}
