package com.openclaw.kbbridge;

import static com.openclaw.kbbridge.Json.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.function.Supplier;

@Service
public class ProcessingService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ProcessingObjects objects;
    private final RemoteClient remote;
    private final PreviewService previews;
    private final LlmService llm;
    private final BridgeProperties properties;
    public ProcessingService(JdbcTemplate db,PlatformTransactionManager manager,ProcessingObjects objects,RemoteClient remote,LlmService llm,PreviewService previews,BridgeProperties properties) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.objects=objects;this.remote=remote;this.llm=llm;this.previews=previews;this.properties=properties;
    }
    private <T> T transaction(Supplier<T> action) { return tx.execute(s->action.get()); }
    private Map<String,Object> one(String sql,Object...args) { var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst(); }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private Map<String,Object> document(String id,boolean lock) {
        db.update("INSERT IGNORE INTO processing_document(document_id) VALUES(?)",id);
        return one("SELECT * FROM processing_document WHERE document_id=?"+(lock?" FOR UPDATE":""),id);
    }
    private String source(Map<String,Object> body) {
        String source=text(body,"sourceMarkdown");
        if(source==null || source.isBlank() || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2100000 || !sha(source).equals(body.get("sourceSha256"))) throw new IllegalArgumentException("Invalid source snapshot");
        return source;
    }
    public Map<String,Object> rewrite(Map<String,Object> body) {return previews.submit(body);}
    public Map<String,Object> publish(String document,Map<String,Object> body) {
        String doc=id(document),release=id(body.get("publicationId")),source=source(body),mode=text(body,"contentType"),fingerprint=sha(write(body));
        long seq=number(body,"publishSeq"),rev=number(body,"sourceRevNo");
        if(seq<=0 || rev<=0 || !Set.of("SOURCE","GUIDE_QA").contains(mode)) throw new IllegalArgumentException("Invalid publication");
        transaction(()-> {
            var master=document(doc,true);
            if("DELETED".equals(master.get("desired_state")))throw new ResponseStatusException(HttpStatus.GONE,"Document was deleted");
            if(seq<number(master,"publish_seq"))conflict("Superseded publication sequence");
            var existing=one("SELECT * FROM processing_release WHERE release_id=?",release);
            if(existing!=null) {
                if(!fingerprint.equals(existing.get("request_hash")) || !doc.equals(existing.get("document_id")))conflict("Release ID reused");
                if("FAILED".equals(existing.get("state")) && seq==number(master,"publish_seq")) db.update("UPDATE processing_release SET state='QUEUED',attempts=0,next_run_at=UTC_TIMESTAMP(3),error_code=NULL,error_message=NULL WHERE release_id=?",release);
                return null;
            }
            if(seq==number(master,"publish_seq"))conflict("Operation sequence reused");
            String title=source.lines().findFirst().orElse("文档").replaceFirst("^#+\\s*", "");
            if(title.codePointCount(0,title.length())>200)title=title.substring(0,title.offsetByCodePoints(0,200));
            String content=source;
            if(mode.equals("GUIDE_QA")) {
                String rewrite=id(body.get("rewriteJobId"));
                var preview=one("SELECT * FROM processing_preview WHERE rewrite_id=? AND document_id=?",rewrite,doc);
                if(preview==null || preview.get("output_json")==null || !"READY".equals(preview.get("state")) || number(preview,"source_rev_no")!=rev || !sha(source).equals(preview.get("source_sha256")))conflict("Rewrite does not match source revision");
                var generated=read(text(preview,"output_json"));
                content="# "+title+"\n\n## 导读\n\n"+generated.get("guideMd")+"\n\n---CHUNK---\n\n## 问答\n\n"+generated.get("qaMd");
            }
            String key="documents/"+doc+"/releases/"+release+"/document.md";
            db.update("UPDATE processing_document SET publish_seq=?,desired_state='PUBLISH',cleanup_complete=FALSE,updated_at=UTC_TIMESTAMP(3) WHERE document_id=?",seq,doc);
            db.update("INSERT INTO processing_release(release_id,document_id,publish_seq,source_rev_no,content_type,request_hash,title,content,sha256,object_key) VALUES(?,?,?,?,?,?,?,?,?,?)",release,doc,seq,rev,mode,fingerprint,title,content,sha(content),key);
            return null;
        });
        var row=one("SELECT * FROM processing_release WHERE release_id=?",release);
        return Map.of("accepted",true,"publicationId",release,"publishSeq",seq,"status",row.get("state"),"objectKey",row.get("object_key"),"sha256",row.get("sha256"));
    }
    public Map<String,Object> withdraw(String document,Map<String,Object> body) {
        String doc=id(document);long seq=number(body,"publishSeq");boolean delete=Boolean.TRUE.equals(body.get("delete"));
        transaction(()-> {
            var master=document(doc,true);
            if(seq<number(master,"publish_seq"))conflict("Superseded withdrawal sequence");
            if("DELETED".equals(master.get("desired_state")) && !delete)throw new ResponseStatusException(HttpStatus.GONE,"Document was deleted");
            db.update("UPDATE processing_document SET publish_seq=?,desired_state=?,effective_release_id=NULL,cleanup_complete=FALSE,updated_at=UTC_TIMESTAMP(3) WHERE document_id=?",seq,delete?"DELETED":"WITHDRAWN",doc);
            db.update("UPDATE processing_release SET state='WITHDRAWN' WHERE document_id=? AND state<>'SUPERSEDED'",doc);
            return null;
        });
        var result=new LinkedHashMap<>(status(doc));result.put("accepted",true);return result;
    }
    public Map<String,Object> status(String document) {
        String doc=id(document);var master=one("SELECT * FROM processing_document WHERE document_id=?",doc);
        if(master==null)return Map.of("publishSeq",0,"status","UNPUBLISHED","cleanupComplete",false,"publications",List.of());
        var releases=db.queryForList("SELECT * FROM processing_release WHERE document_id=? ORDER BY publish_seq DESC",doc);
        var rows=new ArrayList<Map<String,Object>>();
        for(var r:releases) {var view=new LinkedHashMap<String,Object>(Map.of("id",r.get("release_id"),"publishSeq",r.get("publish_seq"),"sourceRevNo",r.get("source_rev_no"),"contentType",r.get("content_type"),"state",r.get("state"),"objectKey",r.get("object_key"),"sha256",r.get("sha256")));view.put("errorCode",r.get("error_code"));view.put("errorMessage",r.get("error_message"));rows.add(view);}
        var result=new LinkedHashMap<String,Object>();result.put("publishSeq",master.get("publish_seq"));result.put("effectivePublicationId",master.get("effective_release_id"));
        String state=text(master,"desired_state");if(state.equals("PUBLISH") && !releases.isEmpty())state=text(releases.getFirst(),"state");
        result.put("status",state);result.put("cleanupComplete",Boolean.TRUE.equals(master.get("cleanup_complete")));result.put("publications",rows);return result;
    }
    public Map<String,Object> query(String question,int limit,boolean debug,String mode) {
        if(question==null || question.isBlank() || question.length()>2000)throw new IllegalArgumentException("Invalid query");
        var warnings=new ArrayList<String>();var alternatives=new ArrayList<String>();long start=System.nanoTime();
        if(properties.queryRewriteEnabled() && mode.equals("enhanced")) {
            try{alternatives.addAll(llm.expand(question));}catch(RuntimeException error){warnings.add("QUERY_REWRITE_UNAVAILABLE");}
        }
        double rewriteMs=(System.nanoTime()-start)/1_000_000.0;
        var request=new LinkedHashMap<String,Object>();request.put("query",question);request.put("queries",alternatives);request.put("limit",Math.clamp(limit,1,50));request.put("debug",debug);request.put("mode",mode);
        var response=remote.vector("POST","query",request);var items=new ArrayList<Map<String,Object>>();
        @SuppressWarnings("unchecked") var hits=(List<Map<String,Object>>)response.getOrDefault("items",List.of());
        for(var hit:hits) {
            var release=one("SELECT r.* FROM processing_release r JOIN processing_document d ON d.document_id=r.document_id WHERE r.release_id=? AND r.document_id=? AND r.state='EFFECTIVE' AND d.effective_release_id=r.release_id AND d.desired_state='PUBLISH'",hit.get("releaseId"),hit.get("documentId"));
            if(release==null || !release.get("sha256").equals(hit.get("sha256")))continue;
            var item=new LinkedHashMap<>(hit);item.put("publicationId",hit.get("releaseId"));item.put("sourceRevNo",release.get("source_rev_no"));item.put("objectKey",release.get("object_key"));items.add(item);
        }
        if(response.get("warnings") instanceof List<?> value)for(Object warning:value)warnings.add(warning.toString());
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("warnings",warnings.stream().distinct().toList());
        if(debug && response.get("debug") instanceof Map<?,?> raw) {
            var details=new LinkedHashMap<String,Object>();for(var entry:raw.entrySet())details.put(entry.getKey().toString(),entry.getValue());
            details.put("queryRewriteMs",rewriteMs);result.put("debug",details);
        }
        return result;
    }
    private boolean current(Map<String,Object> release,String token) {
        return one("SELECT r.release_id FROM processing_release r JOIN processing_document d ON d.document_id=r.document_id WHERE r.release_id=? AND r.lease_token=? AND r.lease_until>UTC_TIMESTAMP(3) AND d.publish_seq=r.publish_seq AND d.desired_state='PUBLISH'",release.get("release_id"),token)!=null;
    }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        try {
            var release=transaction(()-> {
                var row=one("SELECT * FROM processing_release WHERE state IN ('QUEUED','INDEXING') AND next_run_at<=UTC_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP(3)) ORDER BY next_run_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                if(row==null)return null;
                String token=UUID.randomUUID().toString();db.update("UPDATE processing_release SET state='INDEXING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,360,UTC_TIMESTAMP(3)),attempts=attempts+1 WHERE release_id=?",token,row.get("release_id"));row.put("lease_token",token);return row;
            });
            if(release!=null)run(release);
            cleanup();
        } catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Processing will retry: {}",e.getClass().getSimpleName()); }
    }
    private void run(Map<String,Object> release) {
        String doc=text(release,"document_id"),id=text(release,"release_id"),token=text(release,"lease_token");
        try {
            if(!current(release,token)){db.update("UPDATE processing_release SET state='WITHDRAWN',lease_until=NULL WHERE release_id=? AND lease_token=?",id,token);return;}
            transaction(()-> {document(doc,true);if(current(release,token))objects.put(text(release,"object_key"),text(release,"content"));return null;});
            var prepared=remote.vector("POST","versions",Map.of("documentId",doc,"releaseId",id,"sha256",release.get("sha256"),"title",release.get("title"),"content",release.get("content")));
            if(!"READY".equals(prepared.get("status")))throw new IllegalStateException("Index is still preparing");
            if(!current(release,token)) {remote.vector("DELETE","versions/"+id+"?documentId="+doc,null);return;}
            remote.vector("POST","versions/"+id+"/enable",Map.of("documentId",doc));
            boolean enabled=transaction(()-> {
                document(doc,true);if(!current(release,token))return false;
                db.update("UPDATE processing_release SET state='SUPERSEDED' WHERE document_id=? AND state='EFFECTIVE'",doc);
                db.update("UPDATE processing_release SET state='EFFECTIVE',lease_until=NULL,error_code=NULL,error_message=NULL WHERE release_id=? AND lease_token=?",id,token);
                db.update("UPDATE processing_document SET effective_release_id=?,cleanup_complete=FALSE WHERE document_id=?",id,doc);return true;
            });
            if(!enabled)remote.vector("DELETE","versions/"+id+"?documentId="+doc,null);
        } catch(RuntimeException e) {
            db.update("UPDATE processing_release SET state=CASE WHEN attempts>=8 THEN 'FAILED' ELSE 'QUEUED' END,error_code='INDEX_UNAVAILABLE',error_message=CASE WHEN attempts>=8 THEN '索引服务暂时不可用，请手动重试' ELSE '索引服务暂时不可用，正在重试' END,next_run_at=TIMESTAMPADD(SECOND,10,UTC_TIMESTAMP(3)),lease_until=NULL WHERE release_id=? AND lease_token=? AND state='INDEXING'",id,token);
            LoggerFactory.getLogger(getClass()).warn("Release {} will retry: {}",id,e.getClass().getSimpleName());
        }
    }
    private void cleanup() {
        for(var master:db.queryForList("SELECT * FROM processing_document WHERE cleanup_complete=FALSE LIMIT 10")) {
            String doc=text(master,"document_id");boolean deleting="DELETED".equals(master.get("desired_state"));
            boolean publishing="PUBLISH".equals(master.get("desired_state"));
            for(var row:db.queryForList("SELECT release_id,publish_seq,state FROM processing_release WHERE document_id=?",doc)) {
                if(publishing && (Objects.equals(row.get("release_id"),master.get("effective_release_id")) || number(row,"publish_seq")==number(master,"publish_seq")))continue;
                remote.vector("DELETE","versions/"+row.get("release_id")+"?documentId="+doc,null);
            }
            transaction(()-> {
                var latest=document(doc,true);
                if(number(latest,"publish_seq")!=number(master,"publish_seq") || !Objects.equals(latest.get("desired_state"),master.get("desired_state")) || !Objects.equals(latest.get("effective_release_id"),master.get("effective_release_id")))return null;
                if(deleting) { objects.deleteDocument(doc);db.update("DELETE FROM processing_preview WHERE document_id=?",doc);db.update("UPDATE processing_release SET content='',title='' WHERE document_id=?",doc); }
                db.update("UPDATE processing_document SET cleanup_complete=TRUE WHERE document_id=?",doc);return null;
            });
        }
    }
}
