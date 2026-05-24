package com.openclaw.kbbridge.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HashUtil 单元测试。
 */
class HashUtilTest {

    /**
     * 相同内容产生相同哈希。
     */
    @Test
    void sha256_sameContent_producesSameHash() {
        String hash1 = HashUtil.sha256("hello world");
        String hash2 = HashUtil.sha256("hello world");
        assertEquals(hash1, hash2);
    }

    /**
     * 不同内容产生不同哈希。
     */
    @Test
    void sha256_differentContent_producesDifferentHash() {
        String hash1 = HashUtil.sha256("hello");
        String hash2 = HashUtil.sha256("world");
        assertNotEquals(hash1, hash2);
    }

    /**
     * 哈希值为 64 位小写十六进制字符串。
     */
    @Test
    void sha256_producesValidHexString() {
        String hash = HashUtil.sha256("test");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    /**
     * 空字符串也能计算哈希。
     */
    @Test
    void sha256_emptyString_producesHash() {
        String hash = HashUtil.sha256("");
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    /**
     * null 输入抛出 IllegalArgumentException。
     */
    @Test
    void sha256_nullInput_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> HashUtil.sha256(null));
    }
}
