package com.openclaw.kbbridge.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具类。
 */
public final class TimeUtil {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private static final ZoneId UTC = ZoneId.of("UTC");

    private TimeUtil() {
    }

    /**
     * 将 LocalDateTime 格式化为 ISO 8601 UTC 字符串。
     *
     * @param dateTime 本地时间
     * @return ISO 8601 格式字符串，如 2026-04-10T09:30:00Z
     */
    public static String toIsoString(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(ISO_FORMATTER);
    }

    /**
     * 获取当前 UTC 时间。
     *
     * @return 当前 UTC 时间的 LocalDateTime
     */
    public static LocalDateTime nowUtc() {
        return ZonedDateTime.now(UTC).toLocalDateTime();
    }

    /**
     * 判断给定时间是否在指定毫秒数之前。
     *
     * @param dateTime    待检查的时间
     * @param toleranceMs 容差毫秒数
     * @return 如果 dateTime 早于 (当前时间 - toleranceMs) 则返回 true
     */
    public static boolean isExpired(LocalDateTime dateTime, long toleranceMs) {
        if (dateTime == null) {
            return true;
        }
        LocalDateTime threshold = LocalDateTime.now().minusNanos(toleranceMs * 1_000_000);
        return dateTime.isBefore(threshold);
    }
}
