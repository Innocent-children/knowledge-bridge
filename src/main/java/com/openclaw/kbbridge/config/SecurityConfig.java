package com.openclaw.kbbridge.config;

import com.openclaw.kbbridge.security.HmacSignatureFilter;
import com.openclaw.kbbridge.security.RateLimitFilter;
import com.openclaw.kbbridge.security.SignatureValidator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 安全配置类。
 * <p>
 * 通过 {@link FilterRegistrationBean} 注册 {@link HmacSignatureFilter}，
 * 拦截所有 /api/* 请求进行 HMAC-SHA256 签名验证。
 * </p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(KbProperties kbProperties,
                                                                               ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(
                kbProperties.getSecurity().getRateLimitPerMinute(), objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        registration.setName("rateLimitFilter");
        return registration;
    }

    /**
     * 注册 HMAC 签名认证过滤器，拦截 /api/* 路径。
     *
     * @param signatureValidator 签名验证器
     * @param objectMapper       JSON 序列化器
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<HmacSignatureFilter> hmacSignatureFilterRegistration(SignatureValidator signatureValidator, ObjectMapper objectMapper) {
        FilterRegistrationBean<HmacSignatureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HmacSignatureFilter(signatureValidator, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        registration.setName("hmacSignatureFilter");
        return registration;
    }
}
