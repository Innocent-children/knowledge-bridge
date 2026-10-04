package com.openclaw.kbbridge.security;

import com.openclaw.kbbridge.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * HMAC-SHA256 signature authentication filter for /api/* endpoints.
 */
public class HmacSignatureFilter extends OncePerRequestFilter {

    static final String HEADER_SIGNATURE = "X-KB-Signature";
    static final String HEADER_TIMESTAMP = "X-KB-Timestamp";
    static final String HEADER_REQUEST_ID = "X-KB-RequestId";
    private static final Logger log = LoggerFactory.getLogger(HmacSignatureFilter.class);
    /**
     * Paths that the Web Management Console calls directly from the browser (no
     * HMAC signing).
     */
    private static final List<String> CONSOLE_EXACT_PATHS = List.of(
            "/api/v1/chat",
            "/api/v1/ingest/tasks",
            "/api/v1/documents",
            "/api/v1/review/pending",
            "/api/v1/review/approve",
            "/api/v1/review/reject",
            "/api/v1/review/batch");
    private static final List<String> CONSOLE_PREFIX_PATHS = List.of(
            "/api/v1/ingest/status/",
            "/api/v1/ingest/manual",
            "/api/v1/ingest/file",
            "/api/v1/document/",
            "/api/v1/review/");
    private final SignatureValidator signatureValidator;
    private final ObjectMapper objectMapper;

    public HmacSignatureFilter(SignatureValidator signatureValidator, ObjectMapper objectMapper) {
        this.signatureValidator = signatureValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (BlogServiceAuthFilter.isBlogPath(request)) {
            return true;
        }
        if (path.startsWith("/actuator")) {
            return true;
        }
        if (CONSOLE_EXACT_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : CONSOLE_PREFIX_PATHS) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String signature = request.getHeader(HEADER_SIGNATURE);
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String requestId = request.getHeader(HEADER_REQUEST_ID);

        if (signature == null
                || timestamp == null || timestamp.isBlank()
                || requestId == null || requestId.isBlank()) {
            log.warn("Missing signature headers [requestId={}]", requestId != null ? requestId : "unknown");
            writeErrorResponse(response, requestId, "缺少签名请求头");
            return;
        }

        CachedBodyHttpServletRequest cachedRequest;
        try {
            cachedRequest = new CachedBodyHttpServletRequest(request);
        } catch (IOException ex) {
            if (ex.getMessage() != null
                    && ex.getMessage().contains(CachedBodyHttpServletRequest.BODY_TOO_LARGE_MESSAGE)) {
                log.warn("Request body too large [requestId={}]", requestId);
                writePayloadTooLargeResponse(response, requestId, ex.getMessage());
                return;
            }
            throw ex;
        }

        String requestBody = cachedRequest.getCachedBodyString();

        if (!signatureValidator.verify(requestId, timestamp, requestBody, signature)) {
            log.warn("Signature validation failed [requestId={}]", requestId);
            writeErrorResponse(response, requestId, "签名验证失败");
            return;
        }

        if (!signatureValidator.isTimestampValid(timestamp)) {
            log.warn("Timestamp out of tolerance [requestId={}, timestamp={}]", requestId, timestamp);
            writeErrorResponse(response, requestId, "时间戳超出容差范围");
            return;
        }

        filterChain.doFilter(cachedRequest, response);
    }

    private void writeErrorResponse(HttpServletResponse response, String requestId, String message) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse(
                requestId != null ? requestId : "unknown",
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                message,
                Instant.now().toString());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }

    private void writePayloadTooLargeResponse(HttpServletResponse response, String requestId, String message)
            throws IOException {
        ErrorResponse errorResponse = new ErrorResponse(
                requestId != null ? requestId : "unknown",
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "PayloadTooLarge",
                message,
                Instant.now().toString());

        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
