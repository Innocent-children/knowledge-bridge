package com.openclaw.kbbridge.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 内容处理工具类。
 */
public final class MarkdownUtil {

    /**
     * 匹配 Markdown 标题行
     */
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?m)^(#{1,6})\\s+(.+)$");

    /**
     * 匹配 YAML front matter 块（以 --- 开头和结尾）
     */
    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile(
            "\\A\\s*---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);

    /**
     * 语义分块分隔符，LLM 输出中用于标记 chunk 边界
     */
    public static final String CHUNK_DELIMITER = "---CHUNK---";

    /**
     * 匹配分块分隔符行（前后可有空行）
     */
    private static final Pattern CHUNK_DELIMITER_PATTERN = Pattern.compile(
            "\\n*---CHUNK---\\n*");

    /**
     * 匹配 front matter 中的 topic 字段
     */
    private static final Pattern TOPIC_PATTERN = Pattern.compile("(?m)^topic:\\s*(.+)$");

    /**
     * 匹配 front matter 中的 tags 字段（方括号列表格式）
     */
    private static final Pattern TAGS_PATTERN = Pattern.compile("(?m)^tags:\\s*\\[(.+)]$");

    /**
     * 匹配 Q&A 对（问题：... 回答：...）
     */
    private static final Pattern QA_PATTERN = Pattern.compile(
            "(?m)问题[：:]\\s*(.+?)\\s*回答[：:]\\s*(.+?)(?=问题[：:]|$)", Pattern.DOTALL);

    private MarkdownUtil() {
    }

    /**
     * 提取 Markdown 内容中的所有标题文本。
     *
     * @param content Markdown 内容
     * @return 标题文本列表
     */
    public static List<String> extractHeaders(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> headers = new ArrayList<>();
        Matcher matcher = HEADER_PATTERN.matcher(content);
        while (matcher.find()) {
            headers.add(matcher.group(2).trim());
        }
        return headers;
    }

    /**
     * 统计 Markdown 内容中的 Q&A 对数量。
     *
     * @param content Markdown 内容
     * @return Q&A 对数量
     */
    public static int countQaPairs(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        Matcher matcher = QA_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 截取内容前 N 个字符作为摘要，超出部分用省略号替代。
     *
     * @param content   原始内容
     * @param maxLength 最大长度
     * @return 摘要文本
     */
    public static String summarize(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    /**
     * 检查 Markdown 内容是否包含指定的章节标题。
     *
     * @param content        Markdown 内容
     * @param requiredHeader 必需的章节标题
     * @return 包含返回 true
     */
    public static boolean containsHeader(String content, String requiredHeader) {
        if (content == null || requiredHeader == null) {
            return false;
        }
        List<String> headers = extractHeaders(content);
        return headers.stream().anyMatch(h -> h.contains(requiredHeader));
    }

    /**
     * 提取 Markdown 内容中的关键词（简单实现：提取所有非空白、非标点的词）。
     *
     * @param content Markdown 内容
     * @return 关键词列表（去重）
     */
    public static List<String> extractKeywords(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        // 移除 Markdown 语法标记
        String cleaned = content.replaceAll("#{1,6}\\s+", "")
                .replaceAll("[`*_\\[\\]()>~|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // 按空格分词，过滤短词
        return List.copyOf(
                java.util.Arrays.stream(cleaned.split("\\s+"))
                        .filter(w -> w.length() >= 2)
                        .distinct()
                        .toList());
    }

    /**
     * 从 Markdown 内容的 YAML front matter 中提取 topic。
     * <p>
     * 期望格式：
     * 
     * <pre>
     * ---
     * topic: 主题文本
     * tags: [tag1, tag2]
     * ---
     * </pre>
     *
     * @param content 包含 YAML front matter 的 Markdown 内容
     * @return topic 字符串，未找到返回 null
     */
    public static String extractFrontMatterTopic(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher fmMatcher = FRONT_MATTER_PATTERN.matcher(content);
        if (!fmMatcher.find()) {
            return null;
        }
        String frontMatter = fmMatcher.group(1);
        Matcher topicMatcher = TOPIC_PATTERN.matcher(frontMatter);
        if (topicMatcher.find()) {
            return topicMatcher.group(1).trim();
        }
        return null;
    }

    /**
     * 从 Markdown 内容的 YAML front matter 中提取 tags 列表。
     * <p>
     * 期望格式：
     * 
     * <pre>
     * ---
     * topic: 主题文本
     * tags: [tag1, tag2, tag3]
     * ---
     * </pre>
     *
     * @param content 包含 YAML front matter 的 Markdown 内容
     * @return tags 列表，未找到返回空列表
     */
    public static List<String> extractFrontMatterTags(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Matcher fmMatcher = FRONT_MATTER_PATTERN.matcher(content);
        if (!fmMatcher.find()) {
            return List.of();
        }
        String frontMatter = fmMatcher.group(1);
        Matcher tagsMatcher = TAGS_PATTERN.matcher(frontMatter);
        if (tagsMatcher.find()) {
            String tagsStr = tagsMatcher.group(1);
            return Arrays.stream(tagsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of();
    }

    /**
     * 移除 Markdown 内容开头的 YAML front matter 块，返回纯正文内容。
     *
     * @param content 包含 YAML front matter 的 Markdown 内容
     * @return 去除 front matter 后的正文内容
     */
    public static String stripFrontMatter(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        Matcher fmMatcher = FRONT_MATTER_PATTERN.matcher(content);
        if (fmMatcher.find()) {
            return content.substring(fmMatcher.end());
        }
        return content;
    }

    /**
     * 按语义分块分隔符 {@code ---CHUNK---} 将内容拆分为多个 chunk。
     * <p>
     * 拆分前会先去除 YAML front matter。每个 chunk 会被 trim，空 chunk 会被过滤。
     * </p>
     *
     * @param content LLM 输出的包含分块标记的 Markdown 内容
     * @return chunk 列表，每个元素是一个独立的语义块
     */
    public static List<String> splitChunks(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        // 先去除 front matter
        String body = stripFrontMatter(content);
        // 按分隔符拆分
        String[] parts = CHUNK_DELIMITER_PATTERN.split(body);
        List<String> chunks = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                chunks.add(trimmed);
            }
        }
        return chunks;
    }
}
