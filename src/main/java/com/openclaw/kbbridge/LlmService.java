package com.openclaw.kbbridge;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Service
public class LlmService {
    private final BridgeProperties p;
    private final RemoteClient client;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService readers=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    public LlmService(BridgeProperties p,RemoteClient client) {this.p=p;this.client=client;}
    @jakarta.annotation.PreDestroy public void close() {watchdog.shutdownNow();readers.shutdownNow();}
    public String prompt(String name) {
        try(var stream=new ClassPathResource("prompts/"+name+".md").getInputStream()) {return new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
        catch(IOException error) {throw new IllegalStateException("Missing model prompt: "+name,error);}
    }
    private Map<String,Object> request(String instruction,String content,int tokens,boolean stream) {
        return Map.of("model",p.llmModel(),"messages",List.of(Map.of("role","system","content",instruction),Map.of("role","user","content",content)),"max_tokens",tokens,"temperature",0.3,"stream",stream);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> complete(String instruction,String content,int tokens,int timeout) {
        var response=client.call(p.llmBaseUrl(),p.llmApiKey(),"POST","/v1/chat/completions",request(instruction,content,tokens,false),timeout);
        if(!(response.get("choices") instanceof List<?> choices) || choices.isEmpty() || !(choices.getFirst() instanceof Map<?,?> choice))throw invalid();
        if(!"stop".equals(choice.get("finish_reason")))throw new ProcessingFailure("LLM_OUTPUT_TRUNCATED","模型输出未完整结束",false);
        if(!(choice.get("message") instanceof Map<?,?> message) || !(message.get("content") instanceof String raw) || raw.isBlank())throw invalid();
        String text=raw.strip();if(text.startsWith("```json") && text.endsWith("```"))text=text.substring(7,text.length()-3).strip();
        try{return Json.read(text);}catch(RuntimeException error){throw invalid();}
    }
    private ProcessingFailure invalid() {return new ProcessingFailure("INVALID_MODEL_OUTPUT","模型没有返回要求的结构化内容",false);}
    public Map<String,Object> candidate(String content) {
        var value=complete(prompt("candidate"),content,256,Math.min(p.llmTimeoutSeconds(),15));
        if(!(value.get("worthy") instanceof Boolean) || !(value.get("reason") instanceof String))throw invalid();
        return value;
    }
    public List<String> expand(String question) {
        var value=complete(prompt("query"),question,256,2);
        if(!(value.get("queries") instanceof List<?> queries))throw invalid();
        var result=new ArrayList<String>();
        for(Object query:queries) {
            if(!(query instanceof String text) || text.isBlank() || text.length()>2000)throw invalid();
            if(!question.equals(text) && !result.contains(text))result.add(text);
            if(result.size()==2)break;
        }
        return result;
    }
    public String stream(String instruction,String source,Consumer<String> progress) {
        var request=HttpRequest.newBuilder(URI.create(p.llmBaseUrl().replaceAll("/$","")+"/v1/chat/completions"))
            .timeout(Duration.ofSeconds(p.llmTimeoutSeconds())).header("Authorization","Bearer "+p.llmApiKey())
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(request(instruction,source,8192,true)))).build();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(p.llmTimeoutSeconds());
        CompletableFuture<HttpResponse<InputStream>> headers=http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
        InputStream input=null;Future<String> reading=null;ScheduledFuture<?> guard=null;
        try {
            var response=headers.get(p.llmTimeoutSeconds(),TimeUnit.SECONDS);input=response.body();
            if(response.statusCode()/100!=2)throw new ProcessingFailure("LLM_REQUEST_REJECTED","模型调用被拒绝，请检查密钥、模型及额度",response.statusCode()==429 || response.statusCode()>=500);
            InputStream stream=input;var lastRead=new AtomicLong(System.nanoTime());
            guard=watchdog.scheduleAtFixedRate(()->{
                if(System.nanoTime()-lastRead.get()>TimeUnit.SECONDS.toNanos(30))try{stream.close();}catch(IOException ignored){}
            },1,1,TimeUnit.SECONDS);
            reading=readers.submit(()->readEvents(stream,lastRead,progress));
            return reading.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        } catch(TimeoutException error) {throw new ProcessingFailure("LLM_TIMEOUT","模型响应超时，后台将重试",true);}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new ProcessingFailure("LLM_INTERRUPTED","生成被中断，后台将重试",true);}
        catch(ExecutionException error) {if(error.getCause() instanceof ProcessingFailure failure)throw failure;throw new ProcessingFailure("LLM_STREAM_INTERRUPTED","模型流中断，后台将重试",true);}
        finally {
            headers.cancel(true);if(guard!=null)guard.cancel(false);if(reading!=null)reading.cancel(true);
            if(input!=null)try{input.close();}catch(IOException ignored){}
        }
    }
    private String readEvents(InputStream input,AtomicLong lastRead,Consumer<String> progress) throws IOException {
        var text=new StringBuilder();var data=new StringBuilder();String finish=null;
        try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                lastRead.set(System.nanoTime());
                if(line.startsWith("data:")) {if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());}
                if(!line.isEmpty() || data.isEmpty())continue;
                String payload=data.toString();data.setLength(0);if(payload.equals("[DONE]"))break;
                Map<String,Object> event;
                try {event=Json.read(payload);}catch(RuntimeException error){throw invalid();}
                if(!(event.get("choices") instanceof List<?> choices) || choices.isEmpty())continue;
                if(!(choices.getFirst() instanceof Map<?,?> choice))throw invalid();
                if(choice.get("finish_reason")!=null)finish=choice.get("finish_reason").toString();
                if(choice.get("delta") instanceof Map<?,?> delta && delta.get("content")!=null) {
                    if(!(delta.get("content") instanceof String fragment))throw invalid();
                    text.append(fragment);if(text.length()>2_000_000)throw new ProcessingFailure("REWRITE_TOO_LARGE","生成内容超过限制",false);
                    progress.accept(text.toString());
                }
            }
        }
        if(finish==null)throw new ProcessingFailure("LLM_STREAM_INTERRUPTED","模型流未完整结束，后台将重试",true);
        if(!"stop".equals(finish))throw new ProcessingFailure("LLM_OUTPUT_TRUNCATED","模型输出未完整结束，不能确认入库",false);
        if(text.isEmpty())throw invalid();return text.toString();
    }
}
