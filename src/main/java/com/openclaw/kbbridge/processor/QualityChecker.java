package com.openclaw.kbbridge.processor;

import com.openclaw.kbbridge.config.KbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 质量校验器。
 * <p>
 * 对 LLM 重写结果进行多维度质量校验，重点检查输出是否符合 prompt 模板定义的格式规范，
 * 以及内容是否完整、无乱码。不检查具体业务章节内容。
 * <ul>
 * <li>Guide 格式校验：是否包含 chunk 分隔符、Markdown 标题</li>
 * <li>Q&A 格式校验：是否包含 "## Q" 章节、问题/回答标记</li>
 * <li>完整性校验：是否存在截断、乱码</li>
 * <li>Q&A 数量下限（min-qa-count，仅教程类）</li>
 * </ul>
 * 校验失败时返回包含失败原因列表的 {@link QualityCheckResult}。
 * </p>
 */
@Component
public class QualityChecker {

    private static final Logger log = LoggerFactory.getLogger(QualityChecker.class);

    /**
     * Q&A 章节匹配模式：匹配 "## Q" 后跟数字的标题
     */
    private static final Pattern QA_SECTION_PATTERN = Pattern.compile("##\\s*Q\\d+");

    /**
     * Q&A 问题标记模式
     */
    private static final Pattern QA_QUESTION_PATTERN = Pattern.compile("\\*\\*问题\\*\\*[：:]");

    /**
     * Q&A 回答标记模式
     */
    private static final Pattern QA_ANSWER_PATTERN = Pattern.compile("\\*\\*回答\\*\\*[：:]");

    /**
     * Markdown 标题模式（# 开头）
     */
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+.+", Pattern.MULTILINE);

    /**
     * chunk 分隔符
     */
    private static final String CHUNK_SEPARATOR = "---CHUNK---";

    /**
     * 乱码检测：连续 3 个以上的常见乱码字符（替换字符、控制字符等）
     */
    private static final Pattern GARBLED_TEXT_PATTERN = Pattern.compile(
            "[\\uFFFD\\uFFFE\\uFFFF]{2,}|[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]{3,}");

    /**
     * 教程类 sourceType 集合
     */
    private static final java.util.Set<String> TUTORIAL_TYPES = java.util.Set.of("MARKDOWN", "TUTORIAL", "NOTE");

    private final KbProperties kbProperties;

    /**
     * 构造质量校验器。
     *
     * @param kbProperties 统一配置
     */
    public QualityChecker(KbProperties kbProperties) {
        this.kbProperties = kbProperties;
    }

    /**
     * 对重写结果执行质量校验。
     * <p>
     * 依次检查格式规范、完整性和 Q&A 数量下限。
     * 返回包含通过/失败状态和失败原因列表的校验结果。
     * </p>
     *
     * @param rawContent 原始内容（保留形参以兼容调用方，当前实现不再用于长度/关键词校验）
     * @param result     重写处理结果
     * @param sourceType 来源类型（如 MARKDOWN、FEISHU_CHAT）
     * @return 质量校验结果
     */
    public QualityCheckResult check(String rawContent, ProcessResult result, String sourceType) {
        List<String> failures = new ArrayList<>();

        // 1. Guide 格式校验：检查是否符合 guide-template.md 定义的输出格式
        if (result.guideContent() != null) {
            checkGuideFormat(result.guideContent(), failures);
        }

        // 2. Q&A 格式校验：检查是否符合 qa-template.md 定义的输出格式
        if (result.qaContent() != null) {
            checkQaFormat(result.qaContent(), failures);
        }

        // 3. 完整性校验：检查是否有乱码或截断
        checkCompleteness(result, failures);

        // 4. Q&A 数量下限检查（仅教程类）
        if (isTutorialType(sourceType)) {
            checkQaCount(result.qaContent(), failures);
        }

        boolean passed = failures.isEmpty();
        if (!passed) {
            log.warn("质量校验未通过, 失败原因: {}", failures);
        } else {
            log.info("质量校验通过");
        }

        return new QualityCheckResult(passed, failures);
    }

    /**
     * 检查 Guide 内容是否符合 guide-template.md 定义的输出格式。
     * <p>
     * 根据 prompt 模板要求，Guide 输出应包含：
     * <ul>
     * <li>至少一个 Markdown 标题（# 开头）</li>
     * <li>至少一个 chunk 分隔符（---CHUNK---），表示内容被正确分块</li>
     * </ul>
     * 不检查具体章节名称，因为不同类型的内容会产生不同的章节结构。
     * </p>
     *
     * @param guideContent Guide 内容（已去除 front matter）
     * @param failures     失败原因列表（追加）
     */
    void checkGuideFormat(String guideContent, List<String> failures) {
        // 检查是否包含 Markdown 标题
        Matcher headingMatcher = MARKDOWN_HEADING_PATTERN.matcher(guideContent);
        if (!headingMatcher.find()) {
            failures.add("Guide 内容缺少 Markdown 标题结构");
        }

        // 检查是否包含 chunk 分隔符
        if (!guideContent.contains(CHUNK_SEPARATOR)) {
            failures.add("Guide 内容缺少 chunk 分隔符（---CHUNK---），未正确分块");
        }
    }

    /**
     * 检查 Q&A 内容是否符合 qa-template.md 定义的输出格式。
     * <p>
     * 根据 prompt 模板要求，Q&A 输出应包含：
     * <ul>
     * <li>至少一个 "## Q" 格式的章节标题</li>
     * <li>问题标记（**问题**：）和回答标记（**回答**：）</li>
     * </ul>
     * </p>
     *
     * @param qaContent Q&A 内容（已去除 front matter）
     * @param failures  失败原因列表（追加）
     */
    void checkQaFormat(String qaContent, List<String> failures) {
        // 检查 Q&A 章节标题
        Matcher sectionMatcher = QA_SECTION_PATTERN.matcher(qaContent);
        if (!sectionMatcher.find()) {
            failures.add("Q&A 内容缺少标准章节结构（未找到 '## Q' 格式的章节标题）");
            return; // 连章节都没有，后续检查无意义
        }

        // 检查问题标记
        Matcher questionMatcher = QA_QUESTION_PATTERN.matcher(qaContent);
        if (!questionMatcher.find()) {
            failures.add("Q&A 内容缺少问题标记（未找到 '**问题**：' 格式）");
        }

        // 检查回答标记
        Matcher answerMatcher = QA_ANSWER_PATTERN.matcher(qaContent);
        if (!answerMatcher.find()) {
            failures.add("Q&A 内容缺少回答标记（未找到 '**回答**：' 格式）");
        }
    }

    /**
     * 检查重写结果的完整性。
     * <p>
     * 检测以下异常情况：
     * <ul>
     * <li>乱码字符（Unicode 替换字符、控制字符等）</li>
     * <li>内容截断（未闭合的代码块等）</li>
     * <li>空内容（guideContent 和 qaContent 都为空或空白）</li>
     * </ul>
     * </p>
     *
     * @param result   重写处理结果
     * @param failures 失败原因列表（追加）
     */
    void checkCompleteness(ProcessResult result, List<String> failures) {
        String combined = getCombinedContent(result);

        // 空内容检查
        if (combined.isBlank()) {
            failures.add("重写结果为空，Guide 和 Q&A 内容均为空白");
            return;
        }

        // 乱码检测
        Matcher garbledMatcher = GARBLED_TEXT_PATTERN.matcher(combined);
        if (garbledMatcher.find()) {
            failures.add("重写内容包含乱码字符（位置: " + garbledMatcher.start() + "）");
        }

        // 截断检测：未闭合的代码块
        if (result.guideContent() != null) {
            checkTruncation(result.guideContent(), "Guide", failures);
        }
        if (result.qaContent() != null) {
            checkTruncation(result.qaContent(), "Q&A", failures);
        }
    }

    /**
     * 检查单段内容是否存在截断迹象。
     *
     * @param content  内容
     * @param label    内容标签（Guide 或 Q&A，用于错误消息）
     * @param failures 失败原因列表（追加）
     */
    private void checkTruncation(String content, String label, List<String> failures) {
        // 统计代码块开闭标记数量，奇数表示未闭合
        long openCount = countOccurrences(content, "```");
        if (openCount % 2 != 0) {
            failures.add(label + " 内容可能被截断（存在未闭合的代码块）");
        }
    }

    /**
     * 检查 Q&A 数量是否达到下限。
     * <p>
     * 统计 Q&A 内容中 "## Q" 章节的数量，要求不少于 min-qa-count（默认 3）。
     * </p>
     *
     * @param qaContent Q&A 内容
     * @param failures  失败原因列表（追加）
     */
    void checkQaCount(String qaContent, List<String> failures) {
        int minQaCount = kbProperties.getProcessor().getMinQaCount();

        if (qaContent == null || qaContent.isBlank()) {
            failures.add("教程类内容缺少 Q&A: 实际数量=0, 最小要求=" + minQaCount);
            return;
        }

        int qaCount = countQaSections(qaContent);
        if (qaCount < minQaCount) {
            failures.add("Q&A 数量不足: 实际数量=" + qaCount + ", 最小要求=" + minQaCount);
        }
    }

    // ── 内部辅助方法 ──

    /**
     * 判断 sourceType 是否为教程类。
     *
     * @param sourceType 来源类型
     * @return 是否为教程类
     */
    boolean isTutorialType(String sourceType) {
        return sourceType != null && TUTORIAL_TYPES.contains(sourceType.toUpperCase());
    }

    /**
     * 统计 Q&A 内容中 "## Q" 章节的数量。
     *
     * @param qaContent Q&A 内容
     * @return 章节数量
     */
    int countQaSections(String qaContent) {
        Matcher matcher = QA_SECTION_PATTERN.matcher(qaContent);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计子字符串在内容中出现的次数。
     *
     * @param content   内容
     * @param substring 子字符串
     * @return 出现次数
     */
    private long countOccurrences(String content, String substring) {
        long count = 0;
        int index = 0;
        while ((index = content.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    /**
     * 获取重写结果的合并内容。
     *
     * @param result 重写处理结果
     * @return 合并后的内容字符串
     */
    private String getCombinedContent(ProcessResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.guideContent() != null) {
            sb.append(result.guideContent());
        }
        if (result.qaContent() != null) {
            sb.append(result.qaContent());
        }
        return sb.toString();
    }
}
