package com.openclaw.kbbridge.unified;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loopback HTTP, synthetic JSON only; no external KBVector or credentials. */
class UnifiedVectorClientTest {
    HttpServer server;
    UnifiedVectorClient client;
    ObjectMapper mapper=new ObjectMapper();
    List<Map<String,Object>> requests=Collections.synchronizedList(new ArrayList<>());
    String release=UUID.randomUUID().toString(),document=UUID.randomUUID().toString();
    boolean pending;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/bridge/",exchange->{
            var item=new LinkedHashMap<String,Object>();
            item.put("method",exchange.getRequestMethod());item.put("path",exchange.getRequestURI().toString());
            item.put("token",exchange.getRequestHeaders().getFirst("Authorization"));
            String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            item.put("body",raw.isEmpty()?Map.of():mapper.readValue(raw,Map.class));requests.add(item);
            int status=200;
            Map<String,Object> body=Map.of("status","READY","releaseId",release,"documentId",document,"vectorDocumentId","vector-test","jobId","job-test");
            if(exchange.getRequestMethod().equals("POST")&&exchange.getRequestURI().getPath().endsWith("/versions")&&pending) {
                status=409;body=Map.of("code","DOCUMENT_INDEXING");
            } else if(exchange.getRequestMethod().equals("GET"))body=Map.of("status",pending?"INDEXING":"FAILED");
            else if(exchange.getRequestURI().getPath().endsWith("/query"))body=Map.of("items",List.of());
            byte[] bytes=mapper.writeValueAsBytes(body);exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        var properties=new UnifiedProperties();properties.setVectorUrl("http://127.0.0.1:"+server.getAddress().getPort());properties.setVectorToken("synthetic-test-token");
        client=new UnifiedVectorClient(properties,mapper);
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void fixedDatasetHttpContractContainsNoSpaceOrProvisioningRequests() {
        String content="# Original UTF-8\n中文原文";
        var version=Map.<String,Object>of("documentId",document,"releaseId",release,"sha256",UnifiedObjectStore.sha(content),"title","Original","content",content);
        assertEquals("READY",client.prepare(version).get("status"));
        client.enable(document,release);client.delete(document,release);client.query("中文",50);
        assertEquals(List.of("POST","POST","DELETE","POST"),requests.stream().map(r->r.get("method")).toList());
        assertEquals("/api/v1/bridge/versions/"+release+"?documentId="+document,requests.get(2).get("path"));
        assertEquals(version,requests.getFirst().get("body"));
        assertEquals(Map.of("documentId",document),requests.get(1).get("body"));
        assertEquals(Map.of("query","中文","limit",50),requests.get(3).get("body"));
        assertTrue(requests.stream().allMatch(r->r.get("token").equals("Bearer synthetic-test-token")));
        assertTrue(requests.stream().noneMatch(r->r.toString().contains("spaceId")));
    }
    @Test void documentIndexingIsDistinguishedFromOrdinaryFailureAndCanBeRetried() {
        pending=true;
        var version=Map.<String,Object>of("documentId",document,"releaseId",release,"sha256",UnifiedObjectStore.sha("body"),"title","Title","content","body");
        assertThrows(UnifiedVectorClient.IndexPendingException.class,()->client.prepare(version));
        assertEquals("GET",requests.get(1).get("method"));
        assertEquals("/api/v1/bridge/versions/"+release,requests.get(1).get("path"));
        pending=false;assertEquals("READY",client.prepare(version).get("status"));
    }
}
