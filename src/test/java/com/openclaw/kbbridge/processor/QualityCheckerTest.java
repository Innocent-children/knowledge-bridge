package com.openclaw.kbbridge.processor;

import com.openclaw.kbbridge.config.KbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QualityChecker 单元测试。
 * <p>
 * 验证质量校验器的各项校验逻辑：格式规范、完整性、Q&A 数量下限。
 * </p>
 */
class QualityCheckerTest {

    private QualityChecker checker;

    @BeforeEach
    void setUp() {
        KbProperties kbProperties = new KbProperties();
        KbProperties.Processor processorConfig = kbProperties.getProcessor();
        processorConfig.setMinQaCount(3);
        checker = new QualityChecker(kbProperties);
    }

    // ── Q&A 格式校验 ──

    @Test
    void missingQaSectionHeaderFailsFormatCheck() {
        // Q&A 内容没有 "## Q" 格式的章节
        String rawContent = "a".repeat(200);
        String qaContent = "这是一段没有标准 Q&A 结构的内容" + "x".repeat(100);
        ProcessResult result = new ProcessResult(null, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "FEISHU_CHAT");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("Q&A 内容缺少标准章节结构")));
    }

    @Test
    void missingQaQuestionMarkFailsFormatCheck() {
        // Q&A 有章节标题但缺少 **问题**： 标记
        String rawContent = "a".repeat(200);
        String qaContent = "## Q1\n回答内容\n**回答**：这是回答" + "x".repeat(100);
        ProcessResult result = new ProcessResult(null, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "FEISHU_CHAT");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("Q&A 内容缺少问题标记")));
    }

    @Test
    void missingQaAnswerMarkFailsFormatCheck() {
        // Q&A 有章节标题和问题标记但缺少 **回答**： 标记
        String rawContent = "a".repeat(200);
        String qaContent = "## Q1\n**问题**：这是问题\n这是回答内容" + "x".repeat(100);
        ProcessResult result = new ProcessResult(null, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "FEISHU_CHAT");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("Q&A 内容缺少回答标记")));
    }

    // ── Guide 格式校验 ──

    @Test
    void guideMissingHeadingFailsFormatCheck() {
        // Guide 内容没有 Markdown 标题
        String rawContent = "a".repeat(200);
        String guideContent = "这是一段没有标题的 Guide 内容\n---CHUNK---\n另一个 chunk" + "x".repeat(100);
        String qaContent = "## Q1\n**问题**：问题\n**回答**：回答\n---CHUNK---\n## Q2\n**问题**：问题\n**回答**：回答\n---CHUNK---\n## Q3\n**问题**：问题\n**回答**：回答";
        ProcessResult result = new ProcessResult(guideContent, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "MARKDOWN");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("Guide 内容缺少 Markdown 标题结构")));
    }

    @Test
    void guideMissingChunkSeparatorFailsFormatCheck() {
        // Guide 内容没有 chunk 分隔符
        String rawContent = "a".repeat(200);
        String guideContent = "# 标题\n这是一段没有分块的 Guide 内容" + "x".repeat(100);
        String qaContent = "## Q1\n**问题**：问题\n**回答**：回答\n---CHUNK---\n## Q2\n**问题**：问题\n**回答**：回答\n---CHUNK---\n## Q3\n**问题**：问题\n**回答**：回答";
        ProcessResult result = new ProcessResult(guideContent, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "MARKDOWN");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("Guide 内容缺少 chunk 分隔符")));
    }

    // ── 完整性校验 ──

    @Test
    void emptyContentFailsCompletenessCheck() {
        String rawContent = "a".repeat(200);
        ProcessResult result = new ProcessResult("", "", "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "FEISHU_CHAT");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("重写结果为空")));
    }

    @Test
    void garbledTextFailsCompletenessCheck() {
        String rawContent = "a".repeat(200);
        String qaContent = "## Q1\n**问题**：问题\n**回答**：回答\uFFFD\uFFFD\uFFFD" + "x".repeat(100);
        ProcessResult result = new ProcessResult(null, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "FEISHU_CHAT");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("乱码字符")));
    }

    @Test
    void unclosedCodeBlockFailsCompletenessCheck() {
        String rawContent = "a".repeat(200);
        String guideContent = "# 标题\n内容\n---CHUNK---\n## 代码示例\n```java\nSystem.out.println(\"hello\");\n"
                + "x".repeat(50);
        ProcessResult result = new ProcessResult(guideContent, null, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "MARKDOWN");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("可能被截断")));
    }

    // ── Q&A 数量校验 ──

    @Test
    void qaCountBelowMinimumFailsCountCheck() {
        // 教程类内容只有 2 个 Q&A（< minQaCount 3）
        String rawContent = "a".repeat(200);
        String qaContent = "## Q1\n**问题**：问题1\n**回答**：回答1\n---CHUNK---\n## Q2\n**问题**：问题2\n**回答**：回答2"
                + "a".repeat(100);
        ProcessResult result = new ProcessResult(null, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "MARKDOWN");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("Q&A 数量不足")));
    }

    @Test
    void nullQaContentForTutorialTypeFailsQaCountCheck() {
        // 教程类内容但 qaContent 为 null
        String rawContent = "a".repeat(200);
        ProcessResult result = new ProcessResult("# 标题\n内容\n---CHUNK---\n## 第二部分\n更多内容" + "a".repeat(100), null, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "TUTORIAL");

        assertFalse(checkResult.passed());
        assertTrue(checkResult.failures().stream()
                .anyMatch(f -> f.contains("教程类内容缺少 Q&A")));
    }

    // ── 正向测试 ──

    @Test
    void validContentPassesAllChecks() {
        String rawContent = "SpringBoot 配置数据源 application 使用说明文档";
        String guideContent = "# 配置指南\n\nSpringBoot 项目配置数据源的完整说明。\n\n---CHUNK---\n\n## 数据源配置\n\n配置 application datasource";
        String qaContent = "## Q1\n\n**问题**：如何配置 SpringBoot 数据源？\n\n**回答**：在 application 中配置 datasource。\n\n---CHUNK---\n\n## Q2\n\n**问题**：如何使用 datasource？\n\n**回答**：配置 connection pooling。\n\n---CHUNK---\n\n## Q3\n\n**问题**：配置文档在哪？\n\n**回答**：参考使用说明文档。";
        ProcessResult result = new ProcessResult(guideContent, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "MARKDOWN");

        assertTrue(checkResult.passed(), "校验应通过，但失败原因: " + checkResult.failures());
        assertTrue(checkResult.failures().isEmpty());
    }

    @Test
    void nonTutorialTypeSkipsQaCountCheck() {
        // FEISHU_CHAT 不是教程类，即使只有 1 个 Q&A 也不应因 Q&A 数量不足而失败
        String rawContent = "SpringBoot 配置数据源 application 使用说明";
        String qaContent = "## Q1\n\n**问题**：如何配置 SpringBoot 数据源 application？\n\n**回答**：配置说明如下。" + "x".repeat(80);
        ProcessResult result = new ProcessResult(null, qaContent, "v1");

        QualityCheckResult checkResult = checker.check(rawContent, result, "FEISHU_CHAT");

        // 不应有 Q&A 数量不足的失败
        assertTrue(checkResult.failures().stream()
                .noneMatch(f -> f.contains("Q&A 数量不足")));
    }

    // ── 辅助方法测试 ──

    @Test
    void tutorialTypeTriggersQaCountCheck() {
        assertTrue(checker.isTutorialType("MARKDOWN"));
        assertTrue(checker.isTutorialType("TUTORIAL"));
        assertTrue(checker.isTutorialType("NOTE"));
        assertFalse(checker.isTutorialType("FEISHU_CHAT"));
        assertFalse(checker.isTutorialType("ATTACHMENT"));
        assertFalse(checker.isTutorialType(null));
    }

    @Test
    void countQaSectionsCountsCorrectly() {
        String qaContent = "## Q1\n回答1\n## Q2\n回答2\n## Q3\n回答3\n## Q4\n回答4";
        assertEquals(4, checker.countQaSections(qaContent));
    }

    @Test
    void countQaSectionsReturnsZeroForNoSections() {
        assertEquals(0, checker.countQaSections("没有任何 Q&A 章节的内容"));
    }
}
