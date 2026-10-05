package com.openclaw.kbbridge;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("bridge")
public record BridgeProperties(String serviceToken,String sharedSecret,String notesUrl,String notesToken,
    String vectorUrl,String vectorToken,String minioEndpoint,String minioAccessKey,String minioSecretKey,
    @DefaultValue("knowledge-releases") String releasesBucket,String llmBaseUrl,String llmApiKey,String llmModel,
    @DefaultValue("180") int llmTimeoutSeconds,@DefaultValue("5") int searchMaxSources,
    @DefaultValue("2") int processingConcurrency,@DefaultValue("30") int ingestRequestsPerMinute,
    @DefaultValue("120") int queryRequestsPerMinute,@DefaultValue("3000") int sourceMaxChars,
    @DefaultValue("10000") int contextMaxChars,@DefaultValue("true") boolean queryRewriteEnabled) {}
