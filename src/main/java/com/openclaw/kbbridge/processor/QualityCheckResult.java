package com.openclaw.kbbridge.processor;

import java.util.List;

/**
 * 质量校验结果。
 *
 * @param passed   是否通过校验
 * @param failures 校验失败原因列表（通过时为空列表）
 */
public record QualityCheckResult(
        boolean passed,
        List<String> failures) {
}
