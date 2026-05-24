package com.openclaw.kbbridge.security;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * HmacSignatureFilter 单元测试。
 * 验证签名过滤器对各种场景的处理：有效签名放行、缺少请求头返回 401、
 * 无效签名返回 401、过期时间戳返回 401、actuator 路径绕过过滤器。
 */
class HmacSignatureFilterTest {

    private static final String SHARED_SECRET = "test-shared-secret-key";

    private SignatureValidator signatureValidator;
    private HmacSignatureFilter filter;
    private ObjectMapper objectMapper;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        KbProperties props = new KbProperties();
        props.getSecurity().setSharedSecret(SHARED_SECRET);
        props.getSecurity().setTimestampToleranceMs(300000);
        props.getSecurity().setSignatureAlgorithm("HmacSHA256");
        signatureValidator = new SignatureValidator(props);

        objectMapper = new ObjectMapper();
        filter = new HmacSignatureFilter(signatureValidator, objectMapper);
        filterChain = mock(FilterChain.class);
    }

    @Test
    void validSignature_passesThrough() throws ServletException, IOException {
        String requestId = "req-001";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String body = "{\"question\":\"hello\"}";
        String signature = signatureValidator.sign(requestId, timestamp, body);

        MockHttpServletRequest request = buildRequest("/api/v1/query", body);
        request.addHeader(HmacSignatureFilter.HEADER_REQUEST_ID, requestId);
        request.addHeader(HmacSignatureFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(HmacSignatureFilter.HEADER_SIGNATURE, signature);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        // 验证过滤器链被调用（请求放行）
        verify(filterChain, times(1)).doFilter(any(), eq(response));
        assertEquals(200, response.getStatus());
    }

    @Test
    void missingSignatureHeader_returns401() throws ServletException, IOException {
        MockHttpServletRequest request = buildRequest("/api/v1/query", "{}");
        request.addHeader(HmacSignatureFilter.HEADER_REQUEST_ID, "req-001");
        request.addHeader(HmacSignatureFilter.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        // 不设置 X-KB-Signature

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());

        ErrorResponse errorResponse = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertEquals(401, errorResponse.code());
        assertEquals("Unauthorized", errorResponse.error());
        assertEquals("缺少签名请求头", errorResponse.message());
    }

    @Test
    void missingTimestampHeader_returns401() throws ServletException, IOException {
        MockHttpServletRequest request = buildRequest("/api/v1/query", "{}");
        request.addHeader(HmacSignatureFilter.HEADER_REQUEST_ID, "req-001");
        request.addHeader(HmacSignatureFilter.HEADER_SIGNATURE, "some-sig");
        // 不设置 X-KB-Timestamp

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void missingRequestIdHeader_returns401() throws ServletException, IOException {
        MockHttpServletRequest request = buildRequest("/api/v1/query", "{}");
        request.addHeader(HmacSignatureFilter.HEADER_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        request.addHeader(HmacSignatureFilter.HEADER_SIGNATURE, "some-sig");
        // 不设置 X-KB-RequestId

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());

        ErrorResponse errorResponse = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertEquals("unknown", errorResponse.requestId());
    }

    @Test
    void invalidSignature_returns401() throws ServletException, IOException {
        String requestId = "req-002";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String body = "{\"question\":\"hello\"}";

        MockHttpServletRequest request = buildRequest("/api/v1/query", body);
        request.addHeader(HmacSignatureFilter.HEADER_REQUEST_ID, requestId);
        request.addHeader(HmacSignatureFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(HmacSignatureFilter.HEADER_SIGNATURE, "invalid-signature-value");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());

        ErrorResponse errorResponse = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertEquals(requestId, errorResponse.requestId());
        assertEquals("签名验证失败", errorResponse.message());
    }

    @Test
    void expiredTimestamp_returns401() throws ServletException, IOException {
        String requestId = "req-003";
        // 10 分钟前的时间戳（超出 5 分钟容差）
        String timestamp = String.valueOf(System.currentTimeMillis() - 600000);
        String body = "{\"question\":\"hello\"}";
        String signature = signatureValidator.sign(requestId, timestamp, body);

        MockHttpServletRequest request = buildRequest("/api/v1/query", body);
        request.addHeader(HmacSignatureFilter.HEADER_REQUEST_ID, requestId);
        request.addHeader(HmacSignatureFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(HmacSignatureFilter.HEADER_SIGNATURE, signature);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(401, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());

        ErrorResponse errorResponse = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertEquals("时间戳超出容差范围", errorResponse.message());
    }

    @Test
    void actuatorPath_bypassesFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void actuatorMetricsPath_bypassesFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    void apiPath_isFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    void emptyBody_validSignaturePassesThrough() throws ServletException, IOException {
        String requestId = "req-004";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String body = "";
        String signature = signatureValidator.sign(requestId, timestamp, body);

        MockHttpServletRequest request = buildRequest("/api/v1/query", body);
        request.addHeader(HmacSignatureFilter.HEADER_REQUEST_ID, requestId);
        request.addHeader(HmacSignatureFilter.HEADER_TIMESTAMP, timestamp);
        request.addHeader(HmacSignatureFilter.HEADER_SIGNATURE, signature);

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(any(), eq(response));
        assertEquals(200, response.getStatus());
    }

    @Test
    void errorResponse_containsIso8601Timestamp() throws ServletException, IOException {
        MockHttpServletRequest request = buildRequest("/api/v1/query", "{}");
        // 所有请求头都缺失

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        ErrorResponse errorResponse = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertNotNull(errorResponse.timestamp());
        // ISO 8601 格式包含 'T'
        assertTrue(errorResponse.timestamp().contains("T"));
    }

    /**
     * 构建带请求体的 MockHttpServletRequest。
     */
    private MockHttpServletRequest buildRequest(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.setCharacterEncoding("UTF-8");
        return request;
    }
}
