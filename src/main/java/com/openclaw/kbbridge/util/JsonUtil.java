package com.openclaw.kbbridge.util;

import java.util.List;

/**
 * JSON 工具类，提供简单的 JSON 序列化辅助方法。
 * <p>
 * 用于不需要引入完整 ObjectMapper 的场景（如简单列表序列化）。
 * 复杂场景应使用 Spring 管理的 ObjectMapper。
 * </p>
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /**
     * 将字符串列表序列化为 JSON 数组字符串。
     *
     * @param list 字符串列表
     * @return JSON 数组字符串，如 ["a","b","c"]
     */
    public static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(list.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 将 JSON 字符串中的 Unicode 转义序列（如 {@code \\u4f60}）还原为原字符，便于日志可读。
     * <p>
     * RAGFlow 等 Python 后端默认使用 {@code json.dumps(ensure_ascii=True)}，会把所有非 ASCII
     * 字符输出为 {@code \\uXXXX}形式。打印原始响应时这种转义序列对人眼非常不友好，此方法*还原为实际字符（中文、

    emoji 等）。*</p>*<p>*处理规则：*<ul>*<li>{@code \\uXXXX}：保留为字面量，不解码（通常表示原始内容里就包含反斜杠+u）</li>*<li>{@code \\}：保留为字面量</li>*<li>
    {
        @code\\uD800-\\uDBFF}高代理对：和后续低代理对合成一个 Unicode 码点</li>*<li>非法/孤立的{@code\\uXXXX}：原样保留，不抛异常</li>*</ul>*仅用于日志场景，不要用于业务反序列化。*</p>**@param value 可能含{@code\\uXXXX
    }转义序列的字符串，null 时返回"null"*@return 还原后的字符串*/

    public static String unescapeUnicode(String value) {
        if (value == null) {
            return "null";
        }
        int len = value.length();
        if (len < 6) {
            return value;
        }
        StringBuilder sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = value.charAt(i);
            if (c == '\\' && i + 5 < len && value.charAt(i + 1) == 'u'
                    && !(i > 0 && value.charAt(i - 1) == '\\')) {
                // 解析 \\uXXXX
                String hex = value.substring(i + 2, i + 6);
                try {
                    int code = Integer.parseInt(hex, 16);
                    // 高代理对：尝试合并后续低代理
                    if (Character.isHighSurrogate((char) code)
                            && i + 11 < len
                            && value.charAt(i + 6) == '\\'
                            && value.charAt(i + 7) == 'u') {
                        String hex2 = value.substring(i + 8, i + 12);
                        try {
                            int low = Integer.parseInt(hex2, 16);
                            if (Character.isLowSurrogate((char) low)) {
                                sb.appendCodePoint(Character.toCodePoint((char) code, (char) low));
                                i += 12;
                                continue;
                            }
                        } catch (NumberFormatException ignored) {
                            // 回退到单 char 处理
                        }
                    }
                    sb.append((char) code);
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 非法序列，原样保留
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
