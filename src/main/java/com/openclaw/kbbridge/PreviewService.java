package com.openclaw.kbbridge;

import static com.openclaw.kbbridge.Json.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PreviewService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final LlmService llm;
    private final RewriteQuality quality;
    private final ProcessingObjects objects;
    private final Semaphore slots;
    public PreviewService(JdbcTemplate db,PlatformTransactionManager manager,LlmService llm,RewriteQuality quality,ProcessingObjects objects,BridgeProperties properties) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.llm=llm;this.quality=quality;this.objects=objects;
        slots=new Semaphore(properties.processingConcurrency());
    }
    private Map<String,Object> one(String sql,Object...args) {var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    public Map<String,Object> submit(Map<String,Object> input) {
        String doc=id(input.get("noteId")),rewrite=id(input.get("rewriteJobId")),source=text(input,"sourceMarkdown");
        long revision=number(input,"sourceRevNo");
        if(source==null || source.isBlank() || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2100000 || !sha(source).equals(input.get("sourceSha256")) || revision<=0)
            throw new IllegalArgumentException("Invalid source snapshot");
        tx.executeWithoutResult(transaction->{
            db.update("INSERT IGNORE INTO processing_document(document_id) VALUES(?)",doc);
            var document=one("SELECT desired_state FROM processing_document WHERE document_id=? FOR UPDATE",doc);
            if("DELETED".equals(document.get("desired_state")))throw new ResponseStatusException(HttpStatus.GONE,"Document was deleted");
            String fingerprint=sha(write(input));
            db.update("INSERT IGNORE INTO processing_preview(rewrite_id,document_id,source_rev_no,source_sha256,request_hash,input_json) VALUES(?,?,?,?,?,?)",rewrite,doc,revision,sha(source),fingerprint,write(input));
            var prior=one("SELECT request_hash,state FROM processing_preview WHERE rewrite_id=? FOR UPDATE",rewrite);
            if(!fingerprint.equals(prior.get("request_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Rewrite ID reused with different input");
            if("FAILED".equals(prior.get("state")))db.update("UPDATE processing_preview SET state='QUEUED',attempts=0,error_code=NULL,error_message=NULL,next_run_at=UTC_TIMESTAMP(3) WHERE rewrite_id=?",rewrite);
        });
        return snapshot(doc,rewrite);
    }
    public Map<String,Object> snapshot(String document,String rewrite) {
        var row=one("SELECT * FROM processing_preview WHERE document_id=? AND rewrite_id=?",id(document),id(rewrite));
        if(row==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Preview not found");
        var result=new LinkedHashMap<String,Object>();
        result.put("noteId",row.get("document_id"));result.put("rewriteJobId",row.get("rewrite_id"));result.put("sourceRevNo",row.get("source_rev_no"));result.put("sourceSha256",row.get("source_sha256"));
        result.put("status",row.get("state"));result.put("stage",row.get("stage"));result.put("attempts",row.get("attempts"));
        result.put("guideMd",Objects.requireNonNullElse(row.get("partial_guide"),""));result.put("qaMd",Objects.requireNonNullElse(row.get("partial_qa"),""));
        result.put("errorCode",row.get("error_code"));result.put("errorMessage",row.get("error_message"));
        if(row.get("output_json")!=null && "READY".equals(row.get("state")))result.putAll(read(text(row,"output_json")));
        return result;
    }
    public void cancel(String document,String rewrite) {
        db.update("UPDATE processing_preview SET state='CANCELLED',lease_token=NULL,lease_until=NULL,error_code='REWRITE_EXPIRED',error_message='原文已变化或文档已删除' WHERE document_id=? AND rewrite_id=? AND state IN ('QUEUED','RUNNING')",id(document),id(rewrite));
    }
    @Scheduled(fixedDelay=500)
    public void tick() {
        while(slots.tryAcquire()) {
            Map<String,Object> job;
            try { job=tx.execute(transaction->{
                var row=one("SELECT * FROM processing_preview WHERE (state='QUEUED' AND next_run_at<=UTC_TIMESTAMP(3)) OR (state='RUNNING' AND lease_until<=UTC_TIMESTAMP(3)) ORDER BY next_run_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                if(row==null)return null;
                String lease=UUID.randomUUID().toString();
                db.update("UPDATE processing_preview SET state='RUNNING',attempts=attempts+1,lease_token=?,lease_until=TIMESTAMPADD(SECOND,360,UTC_TIMESTAMP(3)),error_code=NULL,error_message=NULL WHERE rewrite_id=?",lease,row.get("rewrite_id"));
                row.put("lease_token",lease);row.put("attempts",number(row,"attempts")+1);return row;
            }); } catch(RuntimeException error) {slots.release();throw error;}
            if(job==null){slots.release();return;}
            Thread.startVirtualThread(()->{try{run(job);}finally{slots.release();}});
        }
    }
    private boolean owns(Map<String,Object> job) {
        return one("SELECT p.rewrite_id FROM processing_preview p JOIN processing_document d ON d.document_id=p.document_id WHERE p.rewrite_id=? AND p.lease_token=? AND p.state='RUNNING' AND p.lease_until>UTC_TIMESTAMP(3) AND d.desired_state<>'DELETED'",job.get("rewrite_id"),job.get("lease_token"))!=null;
    }
    private void checkpoint(Map<String,Object> job,String part,String value,boolean complete) {
        if(!owns(job))throw new ProcessingFailure("REWRITE_EXPIRED","原文已变化或生成已取消",false);
        String columns=part.equals("guide")?"partial_guide":"partial_qa";
        if(complete)columns+=part.equals("guide")?",guide_md":",qa_md";
        String statement=complete?" SET "+columns.split(",")[0]+"=?,"+columns.split(",")[1]+"=?":" SET "+columns+"=?";
        var args=new ArrayList<Object>();args.add(value);if(complete)args.add(value);
        args.add(job.get("rewrite_id"));args.add(job.get("lease_token"));
        int changed=db.update("UPDATE processing_preview"+statement+",stage='"+part.toUpperCase(Locale.ROOT)+"',lease_until=TIMESTAMPADD(SECOND,360,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3) WHERE rewrite_id=? AND lease_token=? AND state='RUNNING'",args.toArray());
        if(changed!=1)throw new ProcessingFailure("REWRITE_EXPIRED","生成已取消",false);
    }
    @SuppressWarnings("unchecked")
    private void run(Map<String,Object> job) {
        String rewrite=text(job,"rewrite_id"),doc=text(job,"document_id");
        try {
            var input=read(text(job,"input_json"));String source=text(input,"sourceMarkdown");
            var allowed=new HashSet<String>();
            if(input.get("allowedAttachmentIds") instanceof List<?> ids)for(Object value:ids)allowed.add(id(value));
            String guide=text(job,"guide_md"),qa=text(job,"qa_md");
            if(guide==null)guide=generate(job,"guide",source,allowed);
            if(qa==null)qa=generate(job,"qa",source,allowed);
            quality.validate(guide,allowed);quality.validate(qa,allowed);
            String prefix="documents/"+doc+"/derived/"+job.get("source_rev_no")+"/"+rewrite+"/";
            var output=new LinkedHashMap<String,Object>();
            output.put("noteId",doc);output.put("rewriteJobId",rewrite);output.put("sourceRevNo",job.get("source_rev_no"));output.put("sourceSha256",job.get("source_sha256"));
            output.put("guideMd",guide);output.put("qaMd",qa);output.put("guideKey",prefix+"guide.md");output.put("qaKey",prefix+"qa.md");
            output.put("guideSha256",sha(guide));output.put("qaSha256",sha(qa));
            tx.executeWithoutResult(transaction->{
                one("SELECT document_id FROM processing_document WHERE document_id=? FOR UPDATE",doc);
                if(!owns(job))throw new ProcessingFailure("REWRITE_EXPIRED","生成已取消",false);
                objects.put(text(output,"guideKey"),text(output,"guideMd"));objects.put(text(output,"qaKey"),text(output,"qaMd"));
                db.update("UPDATE processing_preview SET state='READY',stage='COMPLETE',output_json=?,lease_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE rewrite_id=? AND lease_token=?",write(output),rewrite,job.get("lease_token"));
            });
        } catch(RuntimeException error) {
            var failure=ProcessingFailure.from(error);boolean retry=failure.retryable() && number(job,"attempts")<3;
            db.update("UPDATE processing_preview SET state=?,error_code=?,error_message=?,lease_token=NULL,lease_until=NULL,next_run_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),updated_at=UTC_TIMESTAMP(3) WHERE rewrite_id=? AND lease_token=? AND state='RUNNING'",retry?"QUEUED":"FAILED",failure.code(),failure.getMessage(),Math.min(30,number(job,"attempts")*3),rewrite,job.get("lease_token"));
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Rewrite {}: {}",rewrite,failure.code());
        }
    }
    private String generate(Map<String,Object> job,String part,String source,Set<String> allowed) {
        checkpoint(job,part,"",false);var last=new AtomicLong(System.nanoTime());
        String content=llm.stream(llm.prompt(part),source,partial->{
            if(System.nanoTime()-last.get()>TimeUnit.MILLISECONDS.toNanos(300)) {checkpoint(job,part,partial,false);last.set(System.nanoTime());}
        });
        quality.validate(content,allowed);checkpoint(job,part,content,true);return content;
    }
}
