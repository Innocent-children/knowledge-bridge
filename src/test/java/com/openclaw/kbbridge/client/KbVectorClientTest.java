package com.openclaw.kbbridge.client;

import com.openclaw.kbbridge.config.KbProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbVectorClientTest {
    private MockWebServer server;
    private KbVectorClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        KbProperties properties = new KbProperties();
        properties.getKbVector().setBaseUrl(server.url("/").toString());
        properties.getKbVector().setApiKey("secret");
        properties.getKbVector().setDatasetId("dataset-1");
        client = new KbVectorClient(WebClient.builder(), properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsIdempotentChunksContract() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"document_id\":\"doc-1\",\"job_id\":\"job-1\",\"status\":\"SUCCESS\"}"));
        var result = client.createDocument("title",
                List.of(new KbVectorClient.Chunk("content", "{}")), "task:QA:v1");
        var request = server.takeRequest();
        assertEquals("Bearer secret", request.getHeader("Authorization"));
        assertEquals("/api/v1/datasets/dataset-1/documents", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"idempotency_key\":\"task:QA:v1\""));
        assertTrue(body.contains("\"chunks\""));
        assertEquals("doc-1", result.document_id());
    }
}
