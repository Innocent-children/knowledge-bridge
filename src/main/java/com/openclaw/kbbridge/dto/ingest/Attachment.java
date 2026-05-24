package com.openclaw.kbbridge.dto.ingest;

/**
 * 附件信息。
 *
 * @param name     附件名称
 * @param url      附件 URL
 * @param mimeType MIME 类型
 */
public record Attachment(
                // 附件显示名称（含扩展名）
                String name,
                // 附件可下载的 URL 地址
                String url,
                // MIME 类型，如 application/pdf、image/png
                String mimeType) {
}
