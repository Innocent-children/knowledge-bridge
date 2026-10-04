package com.openclaw.kbbridge.unified;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class UnifiedVectorClient {
    private final UnifiedProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public UnifiedVectorClient(UnifiedProperties properties,ObjectMapper mapper) {this.properties=properties;this.mapper=mapper;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> call(String method,String path,Object body) {
        if (properties.getVectorUrl().isBlank()||properties.getVectorToken().isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Unified KBVector is not configured");
        try {
            String text=body==null?"":mapper.writeValueAsString(body);
            var request=HttpRequest.newBuilder(URI.create(properties.getVectorUrl().replaceAll("/$","")+"/api/v1/bridge/"+path))
                .timeout(Duration.ofSeconds(150)).header("Authorization","Bearer "+properties.getVectorToken())
                .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(text)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(8*1024*1024+1);
                if(response.statusCode()/100!=2||bytes.length>8*1024*1024) throw new IllegalStateException("KBVector operation failed: HTTP "+response.statusCode());
                return bytes.length==0?Map.of():mapper.readValue(bytes,Map.class);
            }
        } catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IllegalStateException("KBVector interrupted",ex); }
        catch(Exception ex) { throw new IllegalStateException("Unified KBVector unavailable",ex); }
    }
    public static class IndexPendingException extends RuntimeException {
        public IndexPendingException(){super("KBVector candidate still indexing");}
    }
    public Map<String,Object> prepare(Map<String,Object> body) {
        try{return call("POST","versions",body);}
        catch(IllegalStateException failure) {
            // A worker crash in KBVector may leave its lease live longer than our ordinary retry budget.
            var state=call("GET","versions/"+UnifiedPaths.id(body.get("releaseId").toString()),null);
            if("INDEXING".equals(state.get("status")))throw new IndexPendingException();
            throw failure;
        }
    }
    public void enable(String document,String release) {
        call("POST","versions/"+UnifiedPaths.id(release)+"/enable",Map.of("documentId",document));
    }
    public void delete(String document,String release) {
        call("DELETE","versions/"+UnifiedPaths.id(release)+"?documentId="+UnifiedPaths.id(document),null);
    }
    public Map<String,Object> query(String query,int limit) {
        return call("POST","query",Map.of("query",query,"limit",limit));
    }
}
