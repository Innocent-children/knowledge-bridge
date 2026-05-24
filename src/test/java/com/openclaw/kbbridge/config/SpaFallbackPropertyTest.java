package com.openclaw.kbbridge.config;

import net.jqwik.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPA 回退路由属性测试。
 * <p>
 * 使用 jqwik 属性测试框架验证：对于任何不含文件扩展名且不以 /api/ 开头的 GET 路径，
 * SpaFallbackController 都会转发到 index.html。
 * </p>
 * <p>
 * Feature: web-management-console, Property 1: SPA Fallback Routing
 * Validates: Requirements 1.6, 11.2
 */
@Tag("Feature: web-management-console, Property 1: SPA Fallback Routing")
class SpaFallbackPropertyTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SpaFallbackController())
            .build();

    /**
     * 对于任何不含点号的单段路径（排除 api 前缀），
     * SPA 回退控制器应返回 200 并转发到 index.html。
     * <p>
     * **Validates: Requirements 1.6, 11.2**
     */
    @Property(tries = 100)
    void singleSegmentPathsForwardToIndexHtml(
            @ForAll("validSpaPathSegments") String segment) throws Exception {
        mockMvc.perform(get("/" + segment))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    /**
     * 对于任何不含点号的双段路径，SPA 回退控制器应返回 200 并转发到 index.html。
     * <p>
     * **Validates: Requirements 1.6, 11.2**
     */
    @Property(tries = 100)
    void twoSegmentPathsForwardToIndexHtml(
            @ForAll("validSpaPathSegments") String seg1,
            @ForAll("validSpaPathSegments") String seg2) throws Exception {
        mockMvc.perform(get("/" + seg1 + "/" + seg2))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    // ========== Custom Arbitrary Providers ==========

    /**
     * 生成有效的 SPA 路径段：仅包含字母，不含点号，不以 api 开头。
     */
    @Provide
    Arbitrary<String> validSpaPathSegments() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(2)
                .ofMaxLength(20)
                .filter(s -> !s.toLowerCase().startsWith("api")
                        && !s.contains(".")
                        && !s.isBlank());
    }
}
