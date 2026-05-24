package com.openclaw.kbbridge.bugcondition;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.config.SecurityConfig;
import com.openclaw.kbbridge.security.HmacSignatureFilter;
import com.openclaw.kbbridge.security.SignatureValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import net.jqwik.api.*;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Bug Condition Exploration Tests for Low Priority Defects (16, 17, 18).
 * <p>
 * These tests encode the EXPECTED (correct) behavior. On unfixed code, they are
 * EXPECTED TO FAIL, confirming the bugs exist.
 * </p>
 *
 * <b>Validates: Requirements 1.16, 1.17, 1.18</b>
 */
class LowDefectsBugConditionTest {

    // ── Defect 16: Missing Health Indicators ──

    /**
     * Defect 16 - Missing Health Indicators:
     * Verify that custom HealthIndicator classes exist for RAGFlow and MinIO.
     * <p>
     * On unfixed code, no such classes exist in the codebase.
     * Expected counterexample: ClassNotFoundException for RagflowHealthIndicator
     * or MinioHealthIndicator.
     * </p>
     *
     * <b>Validates: Requirements 2.16</b>
     */
    @Example
    void healthIndicatorBeansShouldExistForExternalServices() {
        // Check that RagflowHealthIndicator class exists and implements HealthIndicator
        Class<?> ragflowHealthClass = null;
        try {
            ragflowHealthClass = Class.forName("com.openclaw.kbbridge.config.RagflowHealthIndicator");
        } catch (ClassNotFoundException e) {
            // Expected on unfixed code
        }

        // On FIXED code: RagflowHealthIndicator class should exist
        // On UNFIXED code: no such class exists
        assertNotNull(ragflowHealthClass,
                "RagflowHealthIndicator class should exist at "
                        + "com.openclaw.kbbridge.config.RagflowHealthIndicator, "
                        + "but it was not found. /actuator/health will not report RAGFlow status.");

        assertTrue(HealthIndicator.class.isAssignableFrom(ragflowHealthClass),
                "RagflowHealthIndicator should implement HealthIndicator interface, "
                        + "but it does not. Found class: " + ragflowHealthClass.getName());

        // Check that MinioHealthIndicator class exists and implements HealthIndicator
        Class<?> minioHealthClass = null;
        try {
            minioHealthClass = Class.forName("com.openclaw.kbbridge.config.MinioHealthIndicator");
        } catch (ClassNotFoundException e) {
            // Expected on unfixed code
        }

        // On FIXED code: MinioHealthIndicator class should exist
        // On UNFIXED code: no such class exists
        assertNotNull(minioHealthClass,
                "MinioHealthIndicator class should exist at "
                        + "com.openclaw.kbbridge.config.MinioHealthIndicator, "
                        + "but it was not found. /actuator/health will not report MinIO status.");

        assertTrue(HealthIndicator.class.isAssignableFrom(minioHealthClass),
                "MinioHealthIndicator should implement HealthIndicator interface, "
                        + "but it does not. Found class: " + minioHealthClass.getName());
    }

    // ── Defect 17: No Rate Limiting ──

    /**
     * Defect 17 - No Rate Limiting:
     * Verify that a RateLimitFilter class exists and that SecurityConfig registers
     * it as a filter bean.
     * <p>
     * On unfixed code, no rate limiting mechanism exists at all.
     * Expected counterexample: no RateLimitFilter class found, no rate limit
     * filter registration bean in SecurityConfig.
     * </p>
     *
     * <b>Validates: Requirements 2.17</b>
     */
    @Example
    void rateLimitFilterShouldExistAndBeRegistered() {
        // Check that RateLimitFilter class exists
        Class<?> rateLimitFilterClass = null;
        try {
            rateLimitFilterClass = Class.forName("com.openclaw.kbbridge.security.RateLimitFilter");
        } catch (ClassNotFoundException e) {
            // Expected on unfixed code
        }

        // On FIXED code: RateLimitFilter class should exist
        // On UNFIXED code: no such class exists
        assertNotNull(rateLimitFilterClass,
                "RateLimitFilter class should exist at "
                        + "com.openclaw.kbbridge.security.RateLimitFilter, "
                        + "but it was not found. No rate limiting mechanism exists, "
                        + "allowing potential denial-of-service through resource exhaustion.");

        // Check that SecurityConfig has a method that returns
        // FilterRegistrationBean for rate limiting
        boolean hasRateLimitRegistration = false;
        for (Method method : SecurityConfig.class.getDeclaredMethods()) {
            if (method.getReturnType().equals(FilterRegistrationBean.class)
                    && method.getName().toLowerCase().contains("ratelimit")) {
                hasRateLimitRegistration = true;
                break;
            }
        }

        assertTrue(hasRateLimitRegistration,
                "SecurityConfig should have a @Bean method registering RateLimitFilter "
                        + "(method name should contain 'rateLimit' and return FilterRegistrationBean), "
                        + "but none was found. The rate limit filter is not registered in the filter chain.");
    }

    // ── Defect 18: Signature Check Ordering ──

    /**
     * Defect 18 - Signature Check Ordering:
     * Test that HmacSignatureFilter verifies signature before checking timestamp.
     * Send a request with invalid signature but valid timestamp.
     * <p>
     * On unfixed code, timestamp is checked first. When the timestamp is valid
     * but the signature is invalid, the filter responds with "时间戳超出容差范围"
     * only if the timestamp is bad, or proceeds to signature check only after
     * timestamp passes. This means an attacker can probe valid time windows
     * without needing a valid signature.
     * <p>
     * The key insight: on unfixed code, sending a request with INVALID signature
     * and INVALID timestamp returns "时间戳超出容差范围" (timestamp error),
     * proving timestamp is checked first. On fixed code, it should return
     * "签名验证失败" (signature error) because signature is checked first.
     * <p>
     * We test with a valid timestamp + invalid signature. On unfixed code,
     * the timestamp check passes, then the body is cached and signature is
     * verified — this ordering leaks that the timestamp was valid.
     * On fixed code, signature should be checked before timestamp.
     * </p>
     *
     * <b>Validates: Requirements 2.18</b>
     */
    @Property(tries = 10)
    void signatureShouldBeVerifiedBeforeTimestamp(
            @ForAll("invalidSignatures") String invalidSignature) throws Exception {

        KbProperties kbProperties = new KbProperties();
        KbProperties.Security security = new KbProperties.Security();
        security.setSharedSecret("test-secret-key-for-hmac");
        security.setTimestampToleranceMs(300000); // 5 minutes
        security.setSignatureAlgorithm("HmacSHA256");
        kbProperties.setSecurity(security);

        SignatureValidator validator = new SignatureValidator(kbProperties);
        ObjectMapper objectMapper = new ObjectMapper();

        HmacSignatureFilter filter = new HmacSignatureFilter(validator, objectMapper);

        // Create a request with INVALID signature but VALID timestamp
        // On unfixed code: timestamp is checked first (passes), then signature
        // is checked (fails) → response is "签名验证失败"
        // BUT the fact that we got past the timestamp check means the attacker
        // now knows the timestamp was valid (information leak).
        //
        // On fixed code: signature is checked first (fails immediately) →
        // response is "签名验证失败" and timestamp is never checked.
        //
        // To detect the ordering, we check the source code structure:
        // The doFilterInternal method should verify signature BEFORE timestamp.

        // Approach: Use reflection to inspect the method ordering in doFilterInternal.
        // We look at the source code structure of the doFilterInternal method.
        // On unfixed code, isTimestampValid() is called before verify().
        // On fixed code, verify() should be called before isTimestampValid().

        // Alternative approach: Send a request with an EXPIRED timestamp and
        // INVALID signature. On unfixed code (timestamp first), the response
        // will be "时间戳超出容差范围". On fixed code (signature first), the
        // response will be "签名验证失败".

        String requestId = "req-sig-order-test";
        String expiredTimestamp = String.valueOf(System.currentTimeMillis() - 600000); // 10 min ago (expired)
        String requestBody = "{\"test\": \"data\"}";

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        request.setContent(requestBody.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-KB-Signature", invalidSignature);
        request.addHeader("X-KB-Timestamp", expiredTimestamp);
        request.addHeader("X-KB-RequestId", requestId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        // doFilterInternal is protected, so we call doFilter (the public method)
        // which delegates to doFilterInternal via OncePerRequestFilter
        filter.doFilter(request, response, filterChain);

        // The request should be rejected (both timestamp and signature are invalid)
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus(),
                "Request with invalid signature and expired timestamp should be rejected");

        // Parse the error response to check which check failed first
        String responseBody = response.getContentAsString();

        // On FIXED code: signature is checked first → error message is "签名验证失败"
        // On UNFIXED code: timestamp is checked first → error message is "时间戳超出容差范围"
        assertFalse(responseBody.contains("时间戳超出容差范围"),
                "Timestamp should NOT be checked before signature. "
                        + "The error response contains '时间戳超出容差范围' (timestamp out of tolerance), "
                        + "which means timestamp was checked first, leaking time-window validity "
                        + "to an attacker without a valid signature. "
                        + "Expected: signature check first → '签名验证失败'. "
                        + "Response body: " + responseBody);

        assertTrue(responseBody.contains("签名验证失败"),
                "Signature should be verified before timestamp. "
                        + "Expected error message '签名验证失败' (signature verification failed), "
                        + "but got: " + responseBody);

        // Verify the filter chain was NOT invoked (request should be rejected)
        verify(filterChain, never()).doFilter(any(), any());
    }

    // ── Providers ──

    @Provide
    Arbitrary<String> invalidSignatures() {
        return Arbitraries.of(
                "invalid-signature-base64",
                "AAAA",
                "not-a-real-hmac",
                "dGVzdC1pbnZhbGlk", // base64 of "test-invalid"
                "YWJjZGVmZw==", // base64 of "abcdefg"
                "",
                "!@#$%^&*()",
                "0000000000000000000000000000000000000000000=",
                "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=", // base64 of "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                "c2lnbmF0dXJlLXRlc3Q=" // base64 of "signature-test"
        );
    }
}
