package com.openclaw.kbbridge.dto.query;

import com.openclaw.kbbridge.model.enums.QueryRoute;

import java.util.List;

/**
 * 查询响应 DTO（即 EvidencePack 知识证据包）。
 *
 * @param requestId            请求唯一标识
 * @param route                本轮回答模式
 * @param allowModelSupplement 是否允许 OpenClaw 模型补充回答
 * @param sources              知识证据列表（已截断）
 * @param instructions         OpenClaw 需要注入的回答约束指令
 * @param retrievalQuality     检索质量摘要
 */
public record QueryResponse(
                // 回显请求的 requestId，用于客户端对齐
                String requestId,
                // 本轮实际采用的回答模式（KB_ONLY / KB_PLUS_LLM / LLM_ONLY）
                QueryRoute route,
                // 是否允许调用方（OpenClaw）用模型补充回答；KB_ONLY 时为 false
                boolean allowModelSupplement,
                // 返回给上游的知识证据列表（已按 maxSources 截断）
                List<EvidenceSource> sources,
                // 注入到最终回答模板的约束指令，如"必须引用来源"、"未找到时明确告知"等
                List<String> instructions,
                // 检索质量摘要（命中数、置信度、是否截断等）
                RetrievalQuality retrievalQuality) {
}
