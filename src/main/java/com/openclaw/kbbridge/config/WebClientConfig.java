package com.openclaw.kbbridge.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * WebClient 统一配置。
 * <p>
 * 提供预配置的 {@link WebClient.Builder}，设置连接超时、响应超时、TCP keepalive、连接复用策略等底层参数。
 * 各客户端（RagflowClient、LlmClient 等）在此基础上设置各自的 baseUrl 和 header。
 * </p>
 *
 * <h2>长 LLM 请求可靠性设计</h2>
 * <p>
 * LLM 长文档处理可能耗时数分钟到 20 分钟，期间 TCP 连接长时间空闲，容易被 NAT、防火墙或网关
 * 静默丢弃路由表，导致服务端响应回来时连接已经死亡，应用却干等到 responseTimeout 才报错。
 * 为提高可靠性，本配置：
 * <ul>
 * <li><b>禁用连接池复用</b>（核心修复）：每次请求新建独立连接，避免从池中取到中间链路已经
 * 回收的"僵尸"连接。这是 LLM 长请求场景下最有效的防护。</li>
 * <li><b>启用 TCP_NODELAY 与 SO_KEEPALIVE</b>：减少小包延迟，并让操作系统按系统设定
 * 探测死连接（实际探测间隔受 OS 配置限制）。</li>
 * <li><b>放宽 WebFlux codec 缓冲</b>：LLM 响应（Guide + Q&A 双轨重写）可能远超默认 256KB，
 * 调到 16MB 防止 DataBufferLimitException。</li>
 * </ul>
 * </p>
 */
@Configuration
public class WebClientConfig {

    /**
     * WebFlux codec 内存缓冲上限（16MB）。
     * <p>
     * Spring 默认仅 256KB，长 LLM 响应（≥ 50KB Markdown × 双轨）容易撑爆，调高到 16MB
     * 能覆盖绝大多数极端场景，且仅在单次响应期间占用，不会常驻内存。
     * </p>
     */
    private static final int CODEC_MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    /**
     * 创建预配置的 WebClient.Builder Bean。
     *
     * @param kbProperties 统一配置
     * @return 预配置的 WebClient.Builder
     */
    @Bean
    public WebClient.Builder webClientBuilder(KbProperties kbProperties) {
        long llmTimeoutMs = kbProperties.getProcessor().getLlmTimeoutMs();
        // 连接超时取 LLM 超时的 1/10，最少 5 秒，最多 60 秒
        int connectTimeoutMs = (int) Math.max(5000, Math.min(60000, llmTimeoutMs / 10));

        // 关键：禁用连接池，每次新建连接，避免长空闲连接被中间链路丢弃后仍被复用。
        // 这是修复"LLM 已生成响应但应用收不到"问题的核心改动。
        ConnectionProvider connectionProvider = ConnectionProvider.newConnection();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .responseTimeout(Duration.ofMillis(llmTimeoutMs));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(CODEC_MAX_IN_MEMORY_SIZE));
    }
}
