package com.openclaw.kbbridge;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingServiceTest {
    @Test void scopeIsForwardedWithoutChangingThePluginQueryContract() {
        var db=mock(JdbcTemplate.class);var remote=mock(RemoteClient.class);
        when(remote.vector(eq("POST"),eq("query"),anyMap())).thenReturn(Map.of("items",List.of()));
        var service=new ProcessingService(db,mock(PlatformTransactionManager.class),mock(ProcessingObjects.class),remote,mock(LlmService.class),mock(PreviewService.class),mock(BridgeProperties.class));
        String release="22222222-2222-4222-8222-222222222222";
        service.query("范围查询",5,false,"vector",List.of(release));
        var capture=ArgumentCaptor.forClass(Map.class);
        verify(remote).vector(eq("POST"),eq("query"),capture.capture());
        assertEquals(List.of(release),capture.getValue().get("allowedReleaseIds"));
        reset(remote);
        when(remote.vector(eq("POST"),eq("query"),anyMap())).thenReturn(Map.of("items",List.of()));
        service.query("插件原请求",5,false,"vector");
        verify(remote).vector(eq("POST"),eq("query"),capture.capture());
        assertFalse(capture.getValue().containsKey("allowedReleaseIds"));
    }
    @Test void emptyScopeDoesNotCallModelsOrTheVectorService() {
        var remote=mock(RemoteClient.class);var llm=mock(LlmService.class);
        var service=new ProcessingService(mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),mock(ProcessingObjects.class),remote,llm,mock(PreviewService.class),mock(BridgeProperties.class));
        assertEquals(List.of(),service.query("没有可检索版本",5,false,"enhanced",List.of()).get("items"));
        verifyNoInteractions(remote,llm);
    }
    @Test void publicationKeepsEveryGuideAndQuestionChunkSeparate() {
        String guide="## 部署准备\n准备数据库。\n\n---CHUNK---\n\n## 启动服务\n```bash\n./deploy.sh\n```";
        String qa="## 如何部署？\n先准备数据库。\n\n---CHUNK---\n\n## 如何启动？\n执行部署脚本。";
        var saved=publish("GUIDE_QA",guide,qa);
        String content=(String)saved[7];
        var chunks=Arrays.stream(content.split("---CHUNK---",-1)).map(String::strip).toList();
        assertEquals(List.of(
            "# 合成部署说明\n\n## 导读\n\n## 部署准备\n准备数据库。",
            "## 启动服务\n```bash\n./deploy.sh\n```",
            "## 问答\n\n## 如何部署？\n先准备数据库。",
            "## 如何启动？\n执行部署脚本。"
        ),chunks);
        assertEquals(Json.sha(content),saved[8]);
    }
    @Test void sourcePublicationKeepsTheOriginalBytesAndHash() {
        var saved=publish("SOURCE",null,null);
        assertEquals("# 合成部署说明\n原文正文。",saved[7]);
        assertEquals(Json.sha((String)saved[7]),saved[8]);
    }
    private Object[] publish(String mode,String guide,String qa) {
        String doc="11111111-1111-4111-8111-111111111111",release="22222222-2222-4222-8222-222222222222";
        String rewrite="33333333-3333-4333-8333-333333333333",source="# 合成部署说明\n原文正文。";
        var db=mock(JdbcTemplate.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(db.queryForList("SELECT * FROM processing_document WHERE document_id=? FOR UPDATE",doc))
            .thenReturn(List.of(Map.of("desired_state","WITHDRAWN","publish_seq",0)));
        when(db.queryForList("SELECT * FROM processing_release WHERE release_id=?",release))
            .thenReturn(List.of()).thenReturn(List.of(Map.of("state","QUEUED","object_key","synthetic","sha256","synthetic")));
        if(mode.equals("GUIDE_QA")) {
            when(db.queryForList("SELECT * FROM processing_preview WHERE rewrite_id=? AND document_id=?",rewrite,doc))
                .thenReturn(List.of(Map.of("state","READY","source_rev_no",1,"source_sha256",Json.sha(source),
                    "output_json",Json.write(Map.of("guideMd",guide,"qaMd",qa)))));
        }
        var service=new ProcessingService(db,manager,mock(ProcessingObjects.class),mock(RemoteClient.class),
            mock(LlmService.class),mock(PreviewService.class),mock(BridgeProperties.class));
        service.publish(doc,Map.of("publicationId",release,"publishSeq",1,"sourceRevNo",1,
            "sourceMarkdown",source,"sourceSha256",Json.sha(source),"contentType",mode,"rewriteJobId",rewrite));
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(db).update(eq("INSERT INTO processing_release(release_id,document_id,publish_seq,source_rev_no,content_type,request_hash,title,content,sha256,object_key) VALUES(?,?,?,?,?,?,?,?,?,?)"),args.capture());
        return args.getValue();
    }
}
