package com.openclaw.kbbridge.dto.query;

import com.openclaw.kbbridge.model.enums.Confidence;

/**
 * 检索质量摘要。
 *
 * @param hitCount         实际返回的证据条数
 * @param confidence       命中置信度（HIGH / MEDIUM / LOW）
 * @param truncated        是否发生了截断
 * @param originalHitCount 截断前的原始命中数
 */
public record RetrievalQuality(
                // 实际返回给上游的证据条数（截断后）
                int hitCount,
                // 命中置信度枚举：HIGH（高分命中）、MEDIUM（中等）、LOW（低或无）
                Confidence confidence,
                // 是否因 maxSources 或 maxContentLength 发生了截断
                boolean truncated,
                // 截断前的原始命中数，用于观测真实召回规模
                int originalHitCount) {
}
