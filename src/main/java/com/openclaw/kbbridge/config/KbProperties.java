package com.openclaw.kbbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一配置管理，通过 @ConfigurationProperties(prefix = "kb") 绑定所有 kb.* 配置项。
 * 第一阶段实现 ragflow、query、security 配置组。
 * 第二/三阶段将补充 minio、processor、ingest 配置组。
 */
@Data
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    /**
     * RAGFlow 连接配置
     */
    private Ragflow ragflow = new Ragflow();

    /**
     * 查询路由配置
     */
    private Query query = new Query();

    /**
     * 安全配置
     */
    private Security security = new Security();

    // ── Phase 2/3 placeholders ──

    /**
     * MinIO 连接配置（第二阶段）
     */
    private Minio minio = new Minio();

    /**
     * 知识化重写 LLM 配置（第二阶段）
     */
    private Processor processor = new Processor();

    /**
     * 入库配置（第二阶段）
     */
    private Ingest ingest = new Ingest();

    private KbVector kbVector = new KbVector();

    // ── Nested config classes ──

    @Data
    public static class Ragflow {
        private String baseUrl = "http://localhost:9380";
        private String apiKey;
        private String datasetId;
        private long timeoutMs = 5000;
        private int retryMaxAttempts = 3;
        private long retryDelayMs = 1000;
    }

    @Data
    public static class Query {
        private String routeStrategy = "rule";
        private List<Rule> rules = new ArrayList<>();
        private String defaultRoute = "LLM_ONLY";
        private double scoreThreshold = 0.6;
        private int maxSources = 5;
        private int maxContentLength = 2000;
        private int maxTotalLength = 8000;
        /**
         * 是否启用 Memory 检索（KB_ONLY / KB_PLUS_LLM 路由时生效）
         */
        private boolean memoryEnabled = false;
        /**
         * 检索时使用的数据集 ID 列表（为空时使用 ragflow.dataset-id）
         */
        private List<String> datasetIds = new ArrayList<>();
        /**
         * 是否启用 metadata 过滤（仅返回 APPROVED 状态的文档）
         */
        private boolean metadataFilterEnabled = false;
    }

    @Data
    public static class Rule {
        private String pattern;
        private String route;
        private String category;
        private List<String> keywords;
    }

    @Data
    public static class Security {
        private String sharedSecret;
        private long timestampToleranceMs = 300000;
        private String signatureAlgorithm = "HmacSHA256";
        private int rateLimitPerMinute = 60;
    }

    // ── Phase 2/3 nested classes (placeholders) ──

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey;
        private String secretKey;
        private String rawBucket = "kb-raw";
        private String processedBucket = "kb-processed";
    }

    @Data
    public static class Processor {
        private String llmProvider = "openai";
        private String llmModel = "gpt-4o";
        private String llmBaseUrl;
        private String llmApiKey;
        private long llmTimeoutMs = 30000;
        private String guidePromptTemplate = "classpath:prompts/guide-template.md";
        private String qaPromptTemplate = "classpath:prompts/qa-template.md";
        private String processorVersion = "v1";
        private int minQaCount = 3;
        /**
         * 是否使用 OpenAI 兼容的流式响应（SSE）。
         * <p>
         * 长 LLM 请求开启流式后，TCP 上始终有数据流动，可避免被中间链路（NAT、网关、防火墙）
         * 因长时间空闲而静默丢弃路由表，显著提升长文档入库的成功率。
         * 单次调用可通过 options.put("stream", false) 强制关闭。
         * </p>
         */
        private boolean streamEnabled = true;
        /**
         * 流式响应中两个 chunk 之间允许的最长间隔（毫秒）。
         * <p>
         * 触发后认为连接已死，立即抛 ExternalServiceException 而不是干等到 llmTimeoutMs。
         * 默认 120 秒，对绝大多数 LLM（含带 thinking 的推理模型）的首 token 延迟都够宽。
         * </p>
         */
        private long llmStreamIdleTimeoutMs = 120000;
    }

    @Data
    public static class Ingest {
        private int asyncPoolSize = 4;
        private int retryMaxAttempts = 3;
        private long retryDelayMs = 5000;
        private long orphanTimeoutMs = 600000;
        private String contentHashAlgorithm = "SHA-256";
        private long maxFileSizeBytes = 31457280;
        private List<String> supportedFileExtensions = new ArrayList<>(
                List.of("txt", "md", "csv", "json", "pdf", "docx"));
    }

    @Data
    public static class KbVector {
        private String baseUrl = "http://localhost:8000";
        private String apiKey;
        private String datasetId;
        private long timeoutMs = 120000;
    }
}
