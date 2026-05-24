package com.openclaw.kbbridge.security;

import com.openclaw.kbbridge.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private ObjectMapper objectMapper;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filterChain = mock(FilterChain.class);
    }

    @Test
    void requestsWithinLimitPassThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, objectMapper);

        MockHttpServletRequest first = request("192.168.0.10");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, filterChain);

        MockHttpServletRequest second = request("192.168.0.10");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, filterChain);

        verify(filterChain, times(2)).doFilter(any(), any());
        assertEquals(200, firstResponse.getStatus());
        assertEquals(200, secondResponse.getStatus());
    }

    @Test
    void requestExceedingLimitReturns429() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, objectMapper);

        MockHttpServletRequest first = request("192.168.0.11");
        filter.doFilter(first, new MockHttpServletResponse(), filterChain);

        MockHttpServletRequest second = request("192.168.0.11");
        second.addHeader(HmacSignatureFilter.HEADER_REQUEST_ID, "req-rate");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, filterChain);

        assertEquals(429, secondResponse.getStatus());
        ErrorResponse error = objectMapper.readValue(secondResponse.getContentAsString(), ErrorResponse.class);
        assertEquals("req-rate", error.requestId());
        assertEquals("TooManyRequests", error.error());
    }

    @Test
    void differentClientIpsHaveIndependentLimits() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, objectMapper);

        filter.doFilter(request("192.168.0.12"), new MockHttpServletResponse(), filterChain);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(request("192.168.0.13"), secondResponse, filterChain);

        assertEquals(200, secondResponse.getStatus());
        verify(filterChain, times(2)).doFilter(any(), any());
    }

    @Test
    void actuatorPathBypassesRateLimit() {
        RateLimitFilter filter = new RateLimitFilter(1, objectMapper);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertEquals(true, filter.shouldNotFilter(request));
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
