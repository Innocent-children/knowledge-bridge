package com.openclaw.kbbridge.security;

import com.openclaw.kbbridge.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple per-client-IP fixed-window rate limit filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int rateLimitPerMinute;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitFilter(int rateLimitPerMinute, ObjectMapper objectMapper) {
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (rateLimitPerMinute <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        cleanupExpiredWindows();

        long now = System.currentTimeMillis();
        String key = clientKey(request);
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                return new WindowCounter(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });

        if (counter.count().get() > rateLimitPerMinute) {
            writeRateLimitResponse(response, request.getHeader(HmacSignatureFilter.HEADER_REQUEST_ID));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void cleanupExpiredWindows() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, WindowCounter>> iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WindowCounter> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
            }
        }
    }

    private void writeRateLimitResponse(HttpServletResponse response, String requestId) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse(
                requestId != null ? requestId : "unknown",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "TooManyRequests",
                "Rate limit exceeded",
                Instant.now().toString());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }

    private record WindowCounter(long windowStartMillis, AtomicInteger count) {
        boolean isExpired(long now) {
            return now - windowStartMillis >= WINDOW_MILLIS;
        }
    }
}
