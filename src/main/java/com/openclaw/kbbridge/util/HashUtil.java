package com.openclaw.kbbridge.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希工具类。
 * <p>
 * 提供 SHA-256 哈希计算，用于入库内容去重。
 * </p>
 */
public final class HashUtil {

    private HashUtil() {
        // 工具类禁止实例化
    }

    /**
     * 计算字符串的 SHA-256 哈希值（小写十六进制）。
     *
     * @param content 待哈希的内容
     * @return SHA-256 哈希值（64 位小写十六进制字符串）
     * @throws IllegalArgumentException 当 content 为 null 时抛出
     */
    public static String sha256(String content) {
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 内置算法，不应发生
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
