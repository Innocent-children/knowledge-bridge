package com.openclaw.kbbridge.client;

import java.util.Map;

/**
 * LLM 客户端接口，封装对大语言模型的调用。
 * <p>
 * 支持 OpenAI 兼容接口，用于知识化重写和 LLM 路由策略。
 * 调用失败不自动重试（LLM 调用非幂等），失败时抛出 ExternalServiceException。
 * </p>
 */
public interface LlmClient {

    /**
     * 调用 LLM 完成对话，使用默认模型和参数。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 生成的文本内容
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * 调用 LLM 完成对话，支持额外参数覆盖（如 model、temperature 等）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param options      额外参数，可覆盖 model 等默认配置
     * @return LLM 生成的文本内容
     */
    String complete(String systemPrompt, String userPrompt, Map<String, Object> options);
}
