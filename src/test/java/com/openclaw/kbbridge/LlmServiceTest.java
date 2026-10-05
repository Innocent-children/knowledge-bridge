package com.openclaw.kbbridge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmServiceTest {
    private BridgeProperties properties(String url,int timeout) {
        var p=mock(BridgeProperties.class);when(p.llmBaseUrl()).thenReturn(url);
        when(p.llmApiKey()).thenReturn("synthetic");when(p.llmModel()).thenReturn("test-model");
        when(p.llmTimeoutSeconds()).thenReturn(timeout);return p;
    }
    private String event(String text,String finish) {
        var choice=new LinkedHashMap<String,Object>();choice.put("delta",Map.of("content",text));choice.put("finish_reason",finish);
        return "data: "+Json.write(Map.of("choices",List.of(choice)))+"\n\n";
    }
    @Test void streamsIncrementalMarkdownAndRequiresACompleteFinish() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                output.write(event("# 导读",null).getBytes(StandardCharsets.UTF_8));output.flush();
                output.write(event("\n完整正文","stop").getBytes(StandardCharsets.UTF_8));output.flush();
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });server.start();
        try(var service=new ClosingService(properties("http://127.0.0.1:"+server.getAddress().getPort(),5))) {
            var partials=new ArrayList<String>();
            assertEquals("# 导读\n完整正文",service.value.stream("guide","source",partials::add));
            assertEquals(List.of("# 导读","# 导读\n完整正文"),partials);
        } finally {server.stop(0);}
    }
    @Test void partialTextIsNotSuccessfulWhenProviderStopsAtTokenLimit() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()){output.write((event("partial","length")+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));}
        });server.start();
        try(var service=new ClosingService(properties("http://127.0.0.1:"+server.getAddress().getPort(),5))) {
            var failure=assertThrows(ProcessingFailure.class,()->service.value.stream("guide","source",ignored->{}));
            assertEquals("LLM_OUTPUT_TRUNCATED",failure.code());assertFalse(failure.retryable());
        } finally {server.stop(0);}
    }
    @Test void disconnectedStreamCanBeRetriedWithoutAcceptingPartialOutput() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()){output.write(event("partial",null).getBytes(StandardCharsets.UTF_8));}
        });server.start();
        try(var service=new ClosingService(properties("http://127.0.0.1:"+server.getAddress().getPort(),5))) {
            var failure=assertThrows(ProcessingFailure.class,()->service.value.stream("guide","source",ignored->{}));
            assertEquals("LLM_STREAM_INTERRUPTED",failure.code());assertTrue(failure.retryable());
        } finally {server.stop(0);}
    }
    @Test void stalledStreamingBodyHasAnOverallDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange->{
            try {exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(event("partial",null).getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();Thread.sleep(3000);}
            catch(Exception ignored){} finally{exchange.close();}
        });server.start();
        try(var service=new ClosingService(properties("http://127.0.0.1:"+server.getAddress().getPort(),1))) {
            assertTimeout(Duration.ofMillis(2500),()->{
                var failure=assertThrows(ProcessingFailure.class,()->service.value.stream("guide","source",ignored->{}));
                assertEquals("LLM_TIMEOUT",failure.code());assertTrue(failure.retryable());
            });
        } finally{server.stop(0);}
    }
    @Test void nullContentAndEmbeddedJsonAreRejected() {
        var client=mock(RemoteClient.class);var p=properties("http://unused",5);
        var message=new HashMap<String,Object>();message.put("content",null);
        when(client.call(anyString(),anyString(),anyString(),anyString(),any(),anyInt())).thenReturn(Map.of("choices",List.of(Map.of("finish_reason","stop","message",message))));
        var service=new LlmService(p,client);
        try {
            assertThrows(ProcessingFailure.class,()->service.candidate("content"));
            message.put("content","explanation {\"worthy\":true,\"reason\":\"ok\"}");
            assertThrows(ProcessingFailure.class,()->service.candidate("content"));
        } finally{service.close();}
    }
    private static class ClosingService implements AutoCloseable {
        final LlmService value;
        ClosingService(BridgeProperties p){value=new LlmService(p,mock(RemoteClient.class));}
        public void close(){value.close();}
    }
}
