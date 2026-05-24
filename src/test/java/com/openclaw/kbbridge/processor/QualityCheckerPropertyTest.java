package com.openclaw.kbbridge.processor;

import com.openclaw.kbbridge.config.KbProperties;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QualityChecker 属性测试——结构约束。
 * <p>
 * 使用 jqwik 属性测试框架验证质量校验器的结构完整性校验逻辑。
 * 不依赖 Spring 上下文，直接实例化 QualityChecker 进行测试。
 * </p>
 * <p>
 * Feature: knowledge-bridge, Property 13: 质量校验——结构约束
 * Validates: Requirements 12.1
 */
class QualityCheckerPropertyTest {

    private final QualityChecker checker;

    QualityCheckerPropertyTest() {
        KbProperties kbProperties = new KbProperties();
        KbProperties.Processor processorConfig = kbProperties.getProcessor();
        processorConfig.setMinQaCount(3);
        checker = new QualityChecker(kbProperties);
    }

    // ========================================================================
    // Property: Q&A structure check
    // For any Q&A content that does NOT contain the "## Q" pattern followed
    // by a digit, the structure check should fail.
    // ========================================================================

    /**
     * 验证当 Q&A 内容不包含 "## Q" + 数字 模式时，结构校验应失败。
     * <p>
     * Feature: knowledge-bridge, Property 13: 质量校验——结构约束
     * **Validates: Requirements 12.1**
     */
    @Property(tries = 100)
    void qaContentWithoutQPattern_shouldFailStructureCheck(
            @ForAll("rawContentForStructureTest") String rawContent,
            @ForAll("qaContentWithoutQPattern") String qaContent) {

        // Ensure qaContent does NOT contain "## Q" followed by a digit
        Assume.that(!qaContent.matches("(?s).*##\\s*Q\\d.*"));

        ProcessResult result = new ProcessResult(null, qaContent, "FEISHU_CHAT");

        QualityCheckResult checkResult = checker.check(rawContent, result, "FEISHU_CHAT");

        assertFalse(checkResult.passed(), "校验应失败，因为 Q&A 内容缺少 '## Q' + 数字 的章节结构");
        assertTrue(checkResult.failures().stream().anyMatch(f -> f.contains("Q&A 内容缺少标准章节结构")),
                "失败原因应包含'Q&A 内容缺少标准章节结构'，实际: " + checkResult.failures());
    }

    // ========================================================================
    // @Provide methods — custom Arbitrary generators
    // ========================================================================

    /**
     * 生成用于结构测试的 rawContent：长度在 200-500 之间。
     */
    @Provide
    Arbitrary<String> rawContentForStructureTest() {
        return Arbitraries.strings().alpha().ofMinLength(200).ofMaxLength(500);
    }

    /**
     * 生成不包含 "## Q" + 数字 模式的 Q&A 内容。
     * 使用纯字母字符填充，确保不会意外包含该模式。
     */
    @Provide
    Arbitrary<String> qaContentWithoutQPattern() {
        // Generate content that definitely does NOT contain "## Q" followed by a digit.
        // Use only lowercase letters to avoid accidental pattern matches.
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(100)
                .ofMaxLength(400);
    }
}
