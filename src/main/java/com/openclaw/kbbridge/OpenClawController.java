package com.openclaw.kbbridge;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class OpenClawController {
    private final RemoteClient remote;
    private final LlmService llm;
    private final BridgeProperties properties;
    private final QueryPacket packet;
    public OpenClawController(RemoteClient remote,LlmService llm,BridgeProperties properties,QueryPacket packet) {this.remote=remote;this.llm=llm;this.properties=properties;this.packet=packet;}
    @PostMapping("/ingest/manual") public Map<String,Object> manual(@RequestBody Map<String,Object> input) {
        var body=new LinkedHashMap<>(input);return remote.notes("POST","openclaw/ingest",body);
    }
    @GetMapping("/ingest/status/{task}") public Map<String,Object> status(@PathVariable long task) {return remote.notes("GET","openclaw/status/"+task,null);}
    @PostMapping("/ingest/candidate") public Map<String,Object> candidate(@RequestBody Map<String,Object> input) {
        String content=Json.text(input,"content");if(content==null || content.isBlank() || content.length()>2_000_000)throw new IllegalArgumentException("Invalid candidate content");
        var evaluation=llm.candidate(content);boolean worthy=Boolean.TRUE.equals(evaluation.get("worthy"));
        var result=new LinkedHashMap<String,Object>();result.put("requestId",input.get("requestId"));result.put("worthy",worthy);result.put("reason",evaluation.getOrDefault("reason",worthy?"有可保存的知识":"无需保存"));
        if(worthy) {var body=new LinkedHashMap<>(input);var accepted=remote.notes("POST","openclaw/ingest",body);result.put("taskId",accepted.get("taskId"));result.put("duplicate",accepted.get("duplicate"));}
        return result;
    }
    @PostMapping("/ingest/file") public Map<String,Object> file(@RequestParam String requestId,@RequestParam String userId,@RequestParam(required=false) String chatId,@RequestParam String messageId,@RequestParam(defaultValue="false") boolean force,@RequestPart MultipartFile file) throws Exception {
        String name=file.getOriginalFilename()==null?"":file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if(!(name.endsWith(".md") || name.endsWith(".markdown")) || file.isEmpty() || file.getSize()>2097152)throw new IllegalArgumentException("Only UTF-8 Markdown files up to 2 MiB are supported");
        String content;
        try { content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString(); }
        catch(java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("Markdown must use UTF-8"); }
        if(content.startsWith("\uFEFF"))content=content.substring(1);
        if(content.indexOf('\0')>=0)throw new IllegalArgumentException("Markdown must be a text file");
        var body=new LinkedHashMap<String,Object>();body.put("requestId",requestId);body.put("userId",userId);body.put("chatId",chatId==null?"":chatId);body.put("messageIds",List.of(messageId));body.put("sourceType","MARKDOWN");body.put("content",content);body.put("force",force);
        return remote.notes("POST","openclaw/ingest",body);
    }
    @PostMapping("/query") public Map<String,Object> query(@RequestBody Map<String,Object> body) {
        String raw=Json.text(body,"question"),question=packet.question(raw);
        var results=remote.notes("POST","knowledge/query",Map.of("query",question,"limit",properties.searchMaxSources()));
        return packet.build(body.get("requestId"),packet.strict(raw,body),results);
    }
}
