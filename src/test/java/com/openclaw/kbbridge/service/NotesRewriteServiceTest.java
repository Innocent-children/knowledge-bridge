package com.openclaw.kbbridge.service;

import com.openclaw.kbbridge.dto.notes.NotesRewriteRequest;
import com.openclaw.kbbridge.processor.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NotesRewriteServiceTest {
    @Test void fixedRevisionProducesPreviewWithoutIngestion()throws Exception{
        var processor=mock(MarkdownKnowledgeProcessor.class);when(processor.process(anyString(),eq("MARKDOWN"),anyMap())).thenReturn(new ProcessResult("guide","qa","test"));
        var service=new NotesRewriteService(processor);String source="# synthetic content";String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        var request=new NotesRewriteRequest(UUID.randomUUID(),UUID.randomUUID(),7,UUID.randomUUID(),sha,source,List.of());
        var result=service.rewrite(request);assertEquals(request.rewriteJobId().toString(),result.get("rewriteJobId"));assertEquals(7L,result.get("sourceRevNo"));assertEquals("guide",result.get("guideMd"));
        assertThrows(ResponseStatusException.class,()->service.rewrite(new NotesRewriteRequest(request.userId(),request.noteId(),7,request.rewriteJobId(),"0".repeat(64),source,List.of())));
        verify(processor,times(1)).process(anyString(),anyString(),anyMap());
    }
}
