package com.openclaw.kbbridge.service;

import com.openclaw.kbbridge.dto.notes.NotesRewriteRequest;
import com.openclaw.kbbridge.processor.MarkdownKnowledgeProcessor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class NotesRewriteService {
    private final MarkdownKnowledgeProcessor processor;
    public NotesRewriteService(MarkdownKnowledgeProcessor processor){this.processor=processor;}
    /**
     * 博客保存作业与发布状态，此入口只处理指定快照，不创建全局入库任务。
     */
    public Map<String,Object> rewrite(NotesRewriteRequest request){
        byte[] source=request.sourceMarkdown().getBytes(StandardCharsets.UTF_8);
        if(source.length>2097152||!sha(source).equals(request.sourceSha256()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Source hash or length mismatch");
        var result=processor.process(request.sourceMarkdown(),"MARKDOWN",Map.of("userId",request.userId().toString(),"noteId",request.noteId().toString(),"rewriteJobId",request.rewriteJobId().toString(),"sourceRevNo",request.sourceRevNo(),"allowedAttachmentIds",request.allowedAttachmentIds()));
        String guide=result.guideContent(),qa=result.qaContent();
        if(guide==null||qa==null||guide.isBlank()||qa.isBlank()||guide.getBytes(StandardCharsets.UTF_8).length>4194304||qa.getBytes(StandardCharsets.UTF_8).length>4194304)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid rewrite output");
        return Map.of("userId",request.userId().toString(),"noteId",request.noteId().toString(),"rewriteJobId",request.rewriteJobId().toString(),"sourceRevNo",request.sourceRevNo(),"sourceSha256",request.sourceSha256(),"guideMd",guide,"qaMd",qa,"guideSha256",sha(guide.getBytes(StandardCharsets.UTF_8)),"qaSha256",sha(qa.getBytes(StandardCharsets.UTF_8)));
    }
    private String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
