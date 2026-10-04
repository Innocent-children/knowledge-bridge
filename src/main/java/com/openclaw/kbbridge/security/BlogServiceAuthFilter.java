package com.openclaw.kbbridge.security;

import com.openclaw.kbbridge.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** The blog backend has an independent service credential; it does not use OpenClaw HMAC. */
public class BlogServiceAuthFilter extends OncePerRequestFilter {
    private final String token;
    private final ObjectMapper mapper;
    public BlogServiceAuthFilter(String token, ObjectMapper mapper) {
        this.token = token;
        this.mapper = mapper;
    }
    public static boolean isBlogPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/v1/blog") || path.startsWith("/api/v1/blog/");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !isBlogPath(request); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws IOException, ServletException {
        String authorization = request.getHeader("Authorization");
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : request.getHeader("X-KB-Blog-Token");
        if (token == null || token.isBlank() || supplied == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(), new ErrorResponse("unknown", 401, "Unauthorized",
                    "博客服务凭据无效", Instant.now().toString()));
            return;
        }
        chain.doFilter(request, response);
    }
}
