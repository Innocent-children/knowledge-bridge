package com.openclaw.kbbridge.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 缓存请求体的 HttpServletRequest 包装器。
 * <p>
 * 将请求体字节缓存到内存中，使其可以被多次读取：
 * 一次用于签名验证，一次用于下游控制器处理。
 * </p>
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    static final int MAX_BODY_SIZE = 1_048_576;
    static final String BODY_TOO_LARGE_MESSAGE = "Request body exceeds max size";

    private final byte[] cachedBody;

    /**
     * 构造缓存请求体包装器，读取并缓存原始请求体的全部字节。
     *
     * @param request 原始 HTTP 请求
     * @throws IOException 读取请求体失败时抛出
     */
    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_SIZE + 1);
        if (body.length > MAX_BODY_SIZE) {
            throw new IOException(BODY_TOO_LARGE_MESSAGE + ": " + MAX_BODY_SIZE + " bytes");
        }
        this.cachedBody = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 不支持异步读取
            }

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * 获取缓存的请求体字符串（UTF-8 编码）。
     *
     * @return 请求体字符串
     */
    public String getCachedBodyString() {
        return new String(cachedBody, StandardCharsets.UTF_8);
    }
}
