package com.openclaw.kbbridge.config;

import com.openclaw.kbbridge.security.HmacSignatureFilter;
import com.openclaw.kbbridge.security.SignatureValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HMAC 过滤器排除和 SPA 回退控制器单元测试。
 * Requirements: 5.8, 11.2, 11.4
 */
class SpaFallbackAndHmacTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SpaFallbackController())
            .build();

    // ========== SPA Fallback Tests ==========

    @Test
    void spaFallback_forwardsDashboardToIndexHtml() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void spaFallback_forwardsChatToIndexHtml() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void spaFallback_forwardsReviewToIndexHtml() throws Exception {
        mockMvc.perform(get("/review"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void spaFallback_doesNotCatchStaticFiles() throws Exception {
        // Paths with dots (file extensions) should NOT match the SPA fallback pattern
        mockMvc.perform(get("/main.js"))
                .andExpect(status().isNotFound());
    }

    // ========== HMAC Filter Exclusion Tests ==========

    @Test
    void hmacFilter_excludesChatEndpoint() {
        HmacSignatureFilter filter = createFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat");
        assertTrue(shouldNotFilter(filter, request),
                "/api/v1/chat should be excluded from HMAC filter");
    }

    @Test
    void hmacFilter_excludesIngestTasksEndpoint() {
        HmacSignatureFilter filter = createFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ingest/tasks");
        assertTrue(shouldNotFilter(filter, request),
                "/api/v1/ingest/tasks should be excluded from HMAC filter");
    }

    @Test
    void hmacFilter_excludesDocumentsEndpoint() {
        HmacSignatureFilter filter = createFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/documents");
        assertTrue(shouldNotFilter(filter, request),
                "/api/v1/documents should be excluded from HMAC filter");
    }

    @Test
    void hmacFilter_excludesActuatorEndpoint() {
        HmacSignatureFilter filter = createFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        assertTrue(shouldNotFilter(filter, request),
                "/actuator/health should be excluded from HMAC filter");
    }

    @Test
    void hmacFilter_doesNotExcludeQueryEndpoint() {
        HmacSignatureFilter filter = createFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        assertFalse(shouldNotFilter(filter, request),
                "/api/v1/query should NOT be excluded from HMAC filter");
    }

    @Test
    void hmacFilter_excludesIngestManualEndpoint() {
        HmacSignatureFilter filter = createFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ingest/manual");
        assertTrue(shouldNotFilter(filter, request),
                "/api/v1/ingest/manual should be excluded from HMAC filter (console path)");
    }

    @Test
    void hmacFilter_excludesReviewPendingEndpoint() {
        HmacSignatureFilter filter = createFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/review/pending");
        assertTrue(shouldNotFilter(filter, request),
                "/api/v1/review/pending should be excluded from HMAC filter (console path)");
    }

    private HmacSignatureFilter createFilter() {
        SignatureValidator validator = mock(SignatureValidator.class);
        ObjectMapper objectMapper = new ObjectMapper();
        return new HmacSignatureFilter(validator, objectMapper);
    }

    /**
     * Helper to test the protected shouldNotFilter method via a subclass in the
     * same test.
     */
    private boolean shouldNotFilter(HmacSignatureFilter filter, MockHttpServletRequest request) {
        // Use a wrapper that exposes the protected method
        return new TestableHmacFilter(filter).testShouldNotFilter(request);
    }

    /**
     * Testable wrapper that exposes the protected shouldNotFilter method.
     */
    private static class TestableHmacFilter extends HmacSignatureFilter {
        private final HmacSignatureFilter delegate;

        TestableHmacFilter(HmacSignatureFilter delegate) {
            super(null, null);
            this.delegate = delegate;
        }

        boolean testShouldNotFilter(HttpServletRequest request) {
            // Call the delegate's shouldNotFilter via reflection
            try {
                var method = HmacSignatureFilter.class.getDeclaredMethod("shouldNotFilter", HttpServletRequest.class);
                method.setAccessible(true);
                return (boolean) method.invoke(delegate, request);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
