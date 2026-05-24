package com.openclaw.kbbridge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * 静态资源缓存配置。
 * <p>
 * 为 SPA 前端静态文件设置合适的缓存策略：
 * <ul>
 *   <li>index.html — no-cache（确保每次获取最新版本）</li>
 *   <li>/assets/** — 长期缓存（文件名含 hash，内容不变）</li>
 * </ul>
 * </p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // index.html: no-cache to always get the latest version
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());

        // Hashed assets: long-term immutable cache (1 year)
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS)
                        .cachePublic());
    }
}
