package com.openclaw.kbbridge;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
@Component
public class RemoteClient {
    private final BridgeProperties p;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public RemoteClient(BridgeProperties p) { this.p=p; }
    public Map<String,Object> notes(String method,String path,Object body) { return call(p.notesUrl(),p.notesToken(),method,"/internal/"+path,body,30); }
    public Map<String,Object> vector(String method,String path,Object body) { return call(p.vectorUrl(),p.vectorToken(),method,"/api/v1/bridge/"+path,body,path.equals("query")?6:180); }
    public Map<String,Object> call(String base,String token,String method,String path,Object body,int seconds) {
        if(base==null || base.isBlank() || token==null || token.isBlank()) throw new IllegalStateException("Remote service configuration is incomplete");
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            var b=HttpRequest.newBuilder(URI.create(base.replaceAll("/$","")+path)).timeout(Duration.ofSeconds(seconds)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
            b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(Json.write(body)));
            pending=http.sendAsync(b.build(),HttpResponse.BodyHandlers.ofByteArray());
            var response=pending.get(seconds,java.util.concurrent.TimeUnit.SECONDS);
            byte[] bytes=response.body();
                if(response.statusCode()/100!=2 || bytes.length>8*1024*1024) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Remote path {} returned HTTP {}",path,response.statusCode());
                    if(response.statusCode()>=400 && response.statusCode()<500 && response.statusCode()!=429)
                        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(response.statusCode()),"Remote service rejected the input or configuration");
                    throw new IllegalStateException("Remote operation failed: HTTP "+response.statusCode());
                }
                return bytes.length==0?Map.of():Json.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        } catch(java.util.concurrent.TimeoutException e) { pending.cancel(true);throw new IllegalStateException("Remote response timed out",e); }
        catch(InterruptedException e) { if(pending!=null)pending.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException("Remote operation interrupted",e); }
        catch(java.util.concurrent.ExecutionException e) { throw new IllegalStateException("Remote service unavailable",e); }
    }
}
