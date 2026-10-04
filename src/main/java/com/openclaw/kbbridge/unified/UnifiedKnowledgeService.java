package com.openclaw.kbbridge.unified;

import com.openclaw.kbbridge.dto.ingest.*;
import com.openclaw.kbbridge.dto.query.EvidenceSource;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.processor.*;
import com.openclaw.kbbridge.router.IngestRouter;
import com.openclaw.kbbridge.service.FileTextExtractor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static com.openclaw.kbbridge.unified.UnifiedRepository.*;

@Service
public class UnifiedKnowledgeService {
    final UnifiedRepository store;
    final UnifiedObjectStore objects;
    final UnifiedVectorClient vectors;
    final UnifiedProperties properties;
    final ObjectMapper json;
    final IngestRouter router;
    final QualityChecker quality;
    final FileTextExtractor extractor;
    final IngestTaskMapper tasks;
    final KbProperties kb;
    private static final Pattern ATTACHMENT=Pattern.compile("attachment://([0-9a-fA-F-]{36})");
    private static final Pattern EXTERNAL_IMAGE=Pattern.compile("!\\[[^]]*]\\(\\s*(?:https?:|//)",Pattern.CASE_INSENSITIVE);

    public UnifiedKnowledgeService(UnifiedRepository store,UnifiedObjectStore objects,UnifiedVectorClient vectors,
        UnifiedProperties properties,ObjectMapper json,IngestRouter router,QualityChecker quality,
        FileTextExtractor extractor,IngestTaskMapper tasks,KbProperties kb) {
        this.store=store;this.objects=objects;this.vectors=vectors;this.properties=properties;this.json=json;
        this.router=router;this.quality=quality;this.extractor=extractor;this.tasks=tasks;this.kb=kb;
    }
    public static String uuid(String value) {
        try{return UnifiedPaths.id(value);}catch(Exception ex){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid identifier");}
    }
    public static String required(Map<String,Object> body,String key) {
        Object v=body.get(key);if(!(v instanceof String s)||s.isBlank())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Missing "+key);
        return s;
    }
    String encode(Object body) {return json.writeValueAsString(body);}
    @SuppressWarnings("unchecked") Map<String,Object> payload(Map<String,Object> task) {return json.readValue(text(task,"payload_json"),Map.class);}
    static String deterministic(String input) {return UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();}

    Map<String,Object> document(String document,boolean lock) {
        return store.one("SELECT * FROM kb_logical_document WHERE document_id=?"+(lock?" FOR UPDATE":""),uuid(document));
    }
    Map<String,Object> ensureDocument(String document,String source,String ref) {
        try{store.update("INSERT INTO kb_logical_document(document_id,source_ref,source) VALUES(?,?,?)",uuid(document),ref,source);}
        catch(DuplicateKeyException ignored){}
        var found=document(document,false);
        if(found==null||!source.equals(text(found,"source")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Document source conflict");
        if("DELETED".equals(text(found,"desired_state")))throw new ResponseStatusException(HttpStatus.GONE,"Document was permanently deleted");
        return found;
    }
    String source(Map<String,Object> body) {
        String source=required(body,"sourceMarkdown");
        if(source.getBytes(StandardCharsets.UTF_8).length>2097152)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Source too large");
        String hash=required(body,"sourceSha256");
        if(!UnifiedObjectStore.sha(source).equals(hash))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Source hash mismatch");
        return source;
    }
    static long value(Map<String,Object> body,String name) {
        Object val=body.get(name);if(!(val instanceof Number n)||n.longValue()<1)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid "+name);
        return n.longValue();
    }
    Map<String,Object> baseBody(String document,Map<String,Object> input) {
        var copy=new LinkedHashMap<String,Object>(input);
        copy.remove("userId");copy.put("noteId",uuid(document));
        return copy;
    }
    long createJob(String document,String release,String operation,long revision,Long seq,Map<String,Object> body,String identity,String sourceType,String review,String rawKey) {
        String requestId=UnifiedObjectStore.sha(identity);
        String fingerprint=UnifiedObjectStore.sha(encode(body));
        var existing=store.one("SELECT id,request_fingerprint FROM kb_ingest_task WHERE request_id=?",requestId);
        if(existing!=null) {
            if(!fingerprint.equals(text(existing,"request_fingerprint")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotent request content changed");
            return number(existing,"id");
        }
        long id=store.insertTask(requestId,Objects.equals(body.get("source"),"BLOG")?"blog":"openclaw",sourceType,
            String.valueOf(body.getOrDefault("userId","service")), "QUEUED",review,
            String.valueOf(body.getOrDefault("sourceSha256","")),document,release,operation,revision,seq,encode(body),fingerprint,rawKey);
        return id;
    }
    public Map<String,Object> rewrite(Map<String,Object> input) {
        String doc=uuid(required(input,"noteId"));
        String job=uuid(required(input,"rewriteJobId"));
        long revision=value(input,"sourceRevNo");String source=source(input);
        ensureDocument(doc,"BLOG",doc);
        String key=UnifiedPaths.source(doc,revision);
        putTextFenced(doc,null,key,source);
        var body=baseBody(doc,input);body.put("source","BLOG");body.put("contentType","GUIDE_QA");
        long id=store.transaction(()->createJob(doc,job,"PREVIEW",revision,null,body,"preview:"+doc+":"+job,"MARKDOWN","APPROVED",key));
        // The same durable job is used by synchronous preview and restart recovery.
        runSpecific(id);
        var task=store.one("SELECT * FROM kb_ingest_task WHERE id=?",id);
        if(!"COMPLETED".equals(text(task,"status")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Rewrite pending or failed; retry the same job");
        return preview(task);
    }
    Map<String,Object> preview(Map<String,Object> task) {
        String doc=text(task,"document_id"),job=text(task,"release_id");
        long revision=number(task,"source_rev_no");
        String guideKey=UnifiedPaths.derived(doc,revision,job,"guide"),qaKey=UnifiedPaths.derived(doc,revision,job,"qa");
        String guide=new String(objects.get(guideKey,4194304),StandardCharsets.UTF_8);
        String qa=new String(objects.get(qaKey,4194304),StandardCharsets.UTF_8);
        var body=payload(task);
        var result=new LinkedHashMap<String,Object>();
        result.put("noteId",doc);result.put("sourceRevNo",revision);result.put("rewriteJobId",job);
        result.put("sourceSha256",body.get("sourceSha256"));result.put("guideMd",guide);result.put("qaMd",qa);
        result.put("guideKey",guideKey);result.put("qaKey",qaKey);result.put("guideSha256",UnifiedObjectStore.sha(guide));result.put("qaSha256",UnifiedObjectStore.sha(qa));
        return result;
    }
    public Map<String,Object> publish(String document,Map<String,Object> input) {
        String doc=uuid(document);
        long seq=value(input,"publishSeq"),revision=value(input,"sourceRevNo");
        String release=uuid(required(input,"publicationId")),source=source(input);
        String mode=required(input,"contentType");
        if(!Set.of("SOURCE","GUIDE_QA").contains(mode))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid mode");
        ensureDocument(doc,"BLOG",doc);
        String sourceKey=UnifiedPaths.source(doc,revision);putTextFenced(doc,null,sourceKey,source);
        var body=baseBody(doc,input);body.put("source","BLOG");
        store.transaction(()->{
            var master=document(doc,true);
            long current=number(master,"desired_seq");
            if(seq<current)return null;
            if("DELETED".equals(text(master,"desired_state")))throw new ResponseStatusException(HttpStatus.GONE,"Deleted document");
            if(seq==current) {
                var existing=store.one("SELECT * FROM kb_ingest_task WHERE document_id=? AND publish_seq=? AND operation='PUBLISH'",doc,seq);
                if(existing==null||!release.equals(text(existing,"release_id"))||!UnifiedObjectStore.sha(encode(body)).equals(text(existing,"request_fingerprint")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Operation sequence reused");
                return null;
            }
            if(mode.equals("GUIDE_QA"))checkRewrite(doc,body,revision);
            store.update("UPDATE kb_logical_document SET desired_seq=?,desired_state='PUBLISH',source_rev_no=GREATEST(source_rev_no,?),cleanup_complete=FALSE,updated_at=CURRENT_TIMESTAMP WHERE document_id=?",seq,revision,doc);
            createJob(doc,release,"PUBLISH",revision,seq,body,"publish:"+doc+":"+seq,"MARKDOWN","APPROVED",sourceKey);
            createRelease(doc,release,seq,revision,mode,source,body);
            return null;
        });
        return publicationReply(doc,release,seq);
    }
    void createRelease(String doc,String release,long seq,long revision,String mode,String source,Map<String,Object> body) {
        long task=number(store.one("SELECT id FROM kb_ingest_task WHERE release_id=?",release),"id");
        store.update("INSERT INTO kb_document(task_id,knowledge_type,title,review_status,status,metadata_json,version,document_id,release_id,publish_seq,source_rev_no,content_type,created_at,updated_at) VALUES(?,?,?,'APPROVED','PREPARING',?,1,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            task,mode,title(source),encode(Map.of("source",body.getOrDefault("source","OPENCLAW"))),doc,release,seq,revision,mode);
    }
    void checkRewrite(String doc,Map<String,Object> body,long revision) {
        String job=uuid(required(body,"rewriteJobId"));
        var rewrite=store.one("SELECT * FROM kb_ingest_task WHERE document_id=? AND release_id=? AND operation='PREVIEW' AND status='COMPLETED'",doc,job);
        if(rewrite==null||number(rewrite,"source_rev_no")!=revision||!Objects.equals(payload(rewrite).get("sourceSha256"),body.get("sourceSha256")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Rewrite does not match this source revision");
    }
    Map<String,Object> publicationReply(String doc,String release,long seq) {
        var row=store.one("SELECT * FROM kb_document WHERE document_id=? AND release_id=?",doc,release);
        var result=new LinkedHashMap<String,Object>();
        result.put("accepted",row!=null);result.put("publishSeq",seq);result.put("publicationId",release);
        result.put("status",row==null?"STALE":("PREPARING".equals(text(row,"status"))?"QUEUED":text(row,"status")));
        if(row!=null){result.put("objectKey",row.get("object_key"));result.put("sha256",row.get("sha256"));}
        return result;
    }
    public Map<String,Object> withdraw(String document,Map<String,Object> input) {
        String doc=uuid(document);
        long seq=value(input,"publishSeq");
        boolean deleting=Boolean.TRUE.equals(input.get("delete"));
        if(document(doc,false)==null)ensureDocument(doc,"BLOG",doc);
        store.transaction(()->{
            var master=document(doc,true);
            long current=number(master,"desired_seq");
            String state=deleting?"DELETED":"WITHDRAWN";
            if(seq<current)return null;
            if(seq==current) {
                if(!state.equals(text(master,"desired_state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Operation sequence reused");
                return null;
            }
            if("DELETED".equals(text(master,"desired_state"))&&!deleting)throw new ResponseStatusException(HttpStatus.GONE,"Deleted document");
            store.update("UPDATE kb_logical_document SET desired_seq=?,desired_state=?,effective_release_id=NULL,cleanup_complete=FALSE,updated_at=CURRENT_TIMESTAMP WHERE document_id=?",seq,state,doc);
            store.update("UPDATE kb_document SET status='WITHDRAWN',updated_at=CURRENT_TIMESTAMP WHERE document_id=? AND status<>'DELETED'",doc);
            var body=new LinkedHashMap<String,Object>();body.put("delete",deleting);body.put("source","BLOG");
            createJob(doc,UUID.randomUUID().toString(),"CLEANUP",Math.max(1,number(master,"source_rev_no")),seq,body,"withdraw:"+doc+":"+seq,"MARKDOWN","APPROVED",null);
            return null;
        });
        var master=ensureExisting(doc);
        return Map.of("accepted",number(master,"desired_seq")==seq,"publishSeq",number(master,"desired_seq"),"status",text(master,"desired_state"),"cleanupComplete",flag(master,"cleanup_complete"));
    }
    Map<String,Object> ensureExisting(String doc) {
        var master=document(doc,false);if(master==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found");return master;
    }
    public Map<String,Object> status(String document) {
        String doc=uuid(document);
        var master=document(doc,false);
        if(master==null)return Map.of("publishSeq",0,"status","UNPUBLISHED","publications",List.of(),"cleanupComplete",false);
        var result=new LinkedHashMap<String,Object>();
        result.put("publishSeq",number(master,"desired_seq"));result.put("effectivePublicationId",master.get("effective_release_id"));
        result.put("cleanupComplete",flag(master,"cleanup_complete"));
        var releases=store.rows("SELECT * FROM kb_document WHERE document_id=? ORDER BY publish_seq DESC",doc);
        var list=new ArrayList<Map<String,Object>>();
        for(var row:releases) {
            var view=new LinkedHashMap<String,Object>();view.put("id",row.get("release_id"));view.put("publishSeq",number(row,"publish_seq"));
            view.put("sourceRevNo",number(row,"source_rev_no"));view.put("contentType",row.get("content_type"));view.put("state",row.get("status"));
            view.put("objectKey",row.get("object_key"));view.put("sha256",row.get("sha256"));view.put("title",row.get("title"));
            list.add(view);
            if(Objects.equals(master.get("effective_release_id"),row.get("release_id")))result.put("effectiveSourceRevNo",number(row,"source_rev_no"));
        }
        result.putIfAbsent("effectiveSourceRevNo",null);
        String state=text(master,"desired_state");
        if(state.equals("PUBLISH")&&!releases.isEmpty())state=text(releases.getFirst(),"status");
        result.put("status",state);result.put("publications",list);return result;
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> query(String question,int limit) {return queryUnified(question,limit);}
    @SuppressWarnings("unchecked")
    Map<String,Object> queryUnified(String question,int requested) {
        if(question==null||question.isBlank()||question.length()>2000)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid query");
        int limit=Math.clamp(requested,1,50),candidateLimit=Math.min(limit*4,50);
        var remote=vectors.query(question,candidateLimit);
        var hits=(List<Map<String,Object>>)remote.getOrDefault("items",List.of());
        var items=new ArrayList<Map<String,Object>>();
        var seen=new HashSet<String>();
        for(var hit:hits) {
            String doc=String.valueOf(hit.get("documentId")),release=String.valueOf(hit.get("releaseId"));
            if(seen.contains(release))continue;
            var visible=store.one("SELECT r.*,d.source AS source FROM kb_document r JOIN kb_logical_document d ON d.document_id=r.document_id WHERE r.document_id=? AND r.release_id=? AND d.effective_release_id=r.release_id AND d.desired_state NOT IN ('WITHDRAWN','DELETED') AND r.status='EFFECTIVE'",doc,release);
            if(visible==null||!Objects.equals(visible.get("sha256"),hit.get("sha256"))||!Objects.equals(visible.get("ragflow_document_id"),hit.get("vectorDocumentId")))continue;
            seen.add(release);
            var item=new LinkedHashMap<String,Object>();String content=String.valueOf(hit.getOrDefault("content",""));
            item.put("source",visible.get("source"));item.put("noteId",doc);item.put("documentId",doc);item.put("publicationId",release);item.put("sourceRevNo",number(visible,"source_rev_no"));
            item.put("title",visible.get("title"));item.put("content",content);item.put("excerpt",content.substring(0,Math.min(300,content.length())));
            item.put("objectKey",visible.get("object_key"));item.put("sha256",visible.get("sha256"));item.put("score",hit.getOrDefault("score",0));
            items.add(item);if(items.size()>=limit)break;
        }
        items.removeIf(item->store.one("SELECT document_id FROM kb_logical_document WHERE document_id=? AND effective_release_id=? AND desired_state NOT IN ('DELETED','WITHDRAWN')",item.get("documentId"),item.get("publicationId"))==null);
        return Map.of("items",items,"partial",hits.size()>=candidateLimit&&items.size()<limit);
    }
    @SuppressWarnings("unchecked")
    public List<EvidenceSource> legacyQuery(String question,int limit) {
        var result=queryUnified(question,limit);
        var evidence=new ArrayList<EvidenceSource>();
        for(var item:(List<Map<String,Object>>)result.get("items"))evidence.add(new EvidenceSource(
            "kb-unified",String.valueOf(item.get("title")),String.valueOf(item.get("content")),((Number)item.get("score")).doubleValue(),
            Map.of("documentId",item.get("documentId"),"releaseId",item.get("publicationId"),"sourceRevNo",item.get("sourceRevNo"))));
        return evidence;
    }
    public IngestResponse legacyIngest(IngestRequest input) {return legacyText(input,false);}
    public IngestResponse legacyCandidate(IngestRequest input) {return legacyText(input,true);}
    record OriginalFile(String name,String extension,String mime,byte[] bytes,String hash,String message) {}
    IngestResponse legacyText(IngestRequest input,boolean needsReview) {
        return legacyText(input,needsReview,null);
    }
    IngestResponse priorReply(Map<String,Object> prior,IngestRequest input,String hash,boolean review) {
        var old=payload(prior);
        if(!hash.equals(text(prior,"content_hash"))||!Objects.equals(old.get("needsReview"),review)
            ||!Objects.equals(old.get("legacyForce"),input.force())
            ||!Objects.equals(text(prior,"source_type"),input.sourceType()==null?"NOTE":input.sourceType()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Request changed");
        return new IngestResponse(input.requestId(),number(prior,"id"),text(prior,"status"),false);
    }
    IngestResponse legacyText(IngestRequest input,boolean needsReview,OriginalFile file) {
        if(input.content()==null||input.content().isBlank()||input.content().getBytes(StandardCharsets.UTF_8).length>2097152)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid content");
        String type=input.sourceType()==null?"":input.sourceType().toUpperCase(Locale.ROOT);
        if(!Set.of("MARKDOWN","TUTORIAL","NOTE","FEISHU_CHAT","ATTACHMENT").contains(type))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Unsupported sourceType");
        String identity="legacy:"+input.requestId(),request=UnifiedObjectStore.sha(identity);
        String sourceHash=UnifiedObjectStore.sha(input.content()),dedupHash=file==null?sourceHash:file.hash();
        var prior=store.one("SELECT * FROM kb_ingest_task WHERE request_id=?",request);
        if(prior!=null)return priorReply(prior,input,dedupHash,needsReview);
        String ref=input.messageIds()!=null&&!input.messageIds().isEmpty()
            ?"message:"+String.valueOf(input.chatId())+":"+input.messageIds().getFirst():"content:"+dedupHash;
        String doc=deterministic("openclaw:"+ref);
        ensureDocument(doc,"OPENCLAW",ref);
        try {
            return store.transaction(()->{
                var master=document(doc,true);
                if("DELETED".equals(text(master,"desired_state")))throw new ResponseStatusException(HttpStatus.GONE,"Deleted document");
                var existing=store.one("SELECT * FROM kb_ingest_task WHERE request_id=?",request);
                if(existing!=null)return priorReply(existing,input,dedupHash,needsReview);
                if(!input.force()) {
                    var duplicate=store.one("SELECT t.* FROM kb_ingest_task t JOIN kb_logical_document d ON t.document_id=d.document_id WHERE t.content_hash=? AND t.operation='PUBLISH' AND d.desired_state='PUBLISH' AND t.status NOT IN ('FAILED','DISABLED') AND (t.publish_seq=d.desired_seq OR t.release_id=d.effective_release_id) ORDER BY t.id DESC LIMIT 1",dedupHash);
                    if(duplicate!=null)return new IngestResponse(input.requestId(),number(duplicate,"id"),text(duplicate,"status"),true);
                }
                long revision=number(master,"source_rev_no")+1,seq=number(master,"desired_seq")+1;
                String release=UUID.randomUUID().toString(),raw=UnifiedPaths.source(doc,revision);
                var body=new LinkedHashMap<String,Object>();body.put("source","OPENCLAW");body.put("userId",input.userId());body.put("noteId",doc);
                body.put("sourceRevNo",revision);body.put("publishSeq",seq);body.put("publicationId",release);body.put("rewriteJobId",release);
                body.put("sourceSha256",sourceHash);body.put("sourceMarkdown",input.content());body.put("contentType","GUIDE_QA");
                body.put("needsReview",needsReview);body.put("legacyForce",input.force());body.put("legacyRequestId",input.requestId());body.put("allowedAttachmentIds",List.of());
                if(file!=null)body.put("fileSha256",file.hash());
                store.update("UPDATE kb_logical_document SET source_rev_no=?,desired_seq=?,desired_state='PUBLISH',cleanup_complete=FALSE WHERE document_id=?",revision,seq,doc);
                long id=createJob(doc,release,"PUBLISH",revision,seq,body,identity,input.sourceType()==null?"NOTE":input.sourceType(),needsReview?"CANDIDATE":"APPROVED",raw);
                store.update("UPDATE kb_ingest_task SET content_hash=? WHERE id=?",dedupHash,id);
                createRelease(doc,release,seq,revision,"GUIDE_QA",input.content(),body);
                // Queue visibility and original-file registration commit together under the document lock.
                if(file!=null) {
                    objects.put(UnifiedPaths.base(doc)+"source/"+revision+"/original."+file.extension(),file.bytes(),file.mime());
                    store.update("UPDATE kb_ingest_task SET file_name=?,mime_type=?,file_size=?,source_message_id=? WHERE id=?",file.name(),file.mime(),file.bytes().length,file.message(),id);
                }
                return new IngestResponse(input.requestId(),id,"QUEUED",false);
            });
        } catch(DuplicateKeyException ex) {
            var winner=store.one("SELECT * FROM kb_ingest_task WHERE request_id=?",request);
            if(winner==null)throw ex;
            return priorReply(winner,input,dedupHash,needsReview);
        }
    }
    public FileIngestResponse legacyFile(String requestId,String user,String chat,String message,String expectedHash,boolean force,MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>kb.getIngest().getMaxFileSizeBytes())throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid file size");
        String name=Objects.requireNonNullElse(file.getOriginalFilename(),"file.txt");
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!kb.getIngest().getSupportedFileExtensions().contains(ext))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Unsupported file");
        byte[] bytes;
        try{bytes=file.getBytes();}catch(Exception ex){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Unreadable file");}
        String hash=UnifiedObjectStore.sha(bytes);
        if(expectedHash!=null&&!expectedHash.isBlank()&&!expectedHash.equalsIgnoreCase(hash))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"File hash mismatch");
        String source=extractor.extract(name,bytes);
        var ingest=new IngestRequest(requestId,user,chat,List.of(message),source,"ATTACHMENT",null,force);
        var original=new OriginalFile(name,ext,Objects.requireNonNullElse(file.getContentType(),"application/octet-stream"),bytes,hash,message);
        var response=legacyText(ingest,false,original);
        return new FileIngestResponse(requestId,response.taskId(),response.status(),response.duplicate());
    }
    public IngestTaskEntity legacyTask(Long id) {
        var row=store.one("SELECT * FROM kb_ingest_task WHERE id=?",id);
        if(row==null)return null;
        var result=tasks.selectById(id);
        if(result!=null&&row.get("operation")!=null&&row.get("payload_json")!=null) {
            Object request=payload(row).get("legacyRequestId");if(request instanceof String value)result.setRequestId(value);
        }
        return result;
    }
    public void legacyReview(Long id,boolean approved) {
        var row=store.one("SELECT * FROM kb_ingest_task WHERE id=?",id);
        if(row==null||row.get("document_id")==null)return;
        store.transaction(()->{
            var master=document(text(row,"document_id"),true);
            if(number(row,"publish_seq")!=number(master,"desired_seq")||!"PUBLISH".equals(text(master,"desired_state")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Candidate superseded");
            if(!"WAITING_REVIEW".equals(text(row,"status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Candidate is not ready for review");
            store.update("UPDATE kb_ingest_task SET review_status=?,status=?,next_run_at=CURRENT_TIMESTAMP WHERE id=?",
                approved?"APPROVED":"REJECTED",approved?"QUEUED":"DISABLED",id);
            store.update("UPDATE kb_document SET review_status=?,status=? WHERE release_id=?",approved?"APPROVED":"REJECTED",approved?"PREPARING":"REJECTED",row.get("release_id"));return null;
        });
    }
    public void legacyDocumentToggle(Long id,boolean enable) {
        var row=store.one("SELECT * FROM kb_document WHERE id=?",id);
        if(row==null||row.get("document_id")==null)return;
        String doc=text(row,"document_id");
        store.transaction(()->{
            var master=document(doc,true);
            // Blog publication sequences are owned by the blog database. The console must not create an unknown blog release.
            if("BLOG".equals(text(master,"source")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Manage this document's publication in the blog");
            if(enable) {
                if("DELETED".equals(text(master,"desired_state")))throw new ResponseStatusException(HttpStatus.GONE,"Deleted document");
                if(Objects.equals(master.get("effective_release_id"),row.get("release_id")))return null;
                // Repeat this enable while its exact replacement is queued is idempotent.
                var pending=store.one("SELECT * FROM kb_ingest_task WHERE document_id=? AND publish_seq=? AND operation='PUBLISH' AND status IN ('QUEUED','PROCESSING','INDEXING')",doc,number(master,"desired_seq"));
                if(pending!=null&&Objects.equals(payload(pending).get("consoleSourceReleaseId"),row.get("release_id")))return null;
                var original=store.one("SELECT * FROM kb_ingest_task WHERE id=?",row.get("task_id"));
                if(original==null||original.get("payload_json")==null)throw new ResponseStatusException(HttpStatus.GONE,"Original source unavailable");
                var body=new LinkedHashMap<String,Object>(payload(original));
                long seq=number(master,"desired_seq")+1,revision=number(row,"source_rev_no");
                String release=UUID.randomUUID().toString();body.put("publicationId",release);body.put("rewriteJobId",release);body.put("publishSeq",seq);body.put("consoleSourceReleaseId",row.get("release_id"));
                store.update("UPDATE kb_logical_document SET desired_seq=?,desired_state='PUBLISH',cleanup_complete=FALSE WHERE document_id=?",seq,doc);
                createJob(doc,release,"PUBLISH",revision,seq,body,"console-enable:"+doc+":"+seq,text(original,"source_type"),"APPROVED",text(original,"raw_object_key"));
                createRelease(doc,release,seq,revision,text(row,"content_type"),required(body,"sourceMarkdown"),body);
                return null;
            }
            if(!Objects.equals(master.get("effective_release_id"),row.get("release_id")))return null;
            long seq=number(master,"desired_seq")+1;
            store.update("UPDATE kb_logical_document SET desired_seq=?,desired_state='WITHDRAWN',effective_release_id=NULL,cleanup_complete=FALSE WHERE document_id=?",seq,doc);
            store.update("UPDATE kb_document SET status='WITHDRAWN' WHERE document_id=?",doc);
            createJob(doc,UUID.randomUUID().toString(),"CLEANUP",number(row,"source_rev_no"),seq,Map.of("delete",false),"console-disable:"+doc+":"+seq,"MARKDOWN","APPROVED",null);return null;
        });
    }
    static String title(String source) {
        String title=source.lines().filter(s->!s.isBlank()).findFirst().orElse("Knowledge").replaceFirst("^#+\\s*","");
        return title.substring(0,Math.min(256,title.length()));
    }
    public void runSpecific(long id) {
        Map<String,Object> claimed=claim(id);
        if(claimed!=null)execute(claimed);
    }
    Map<String,Object> claim(long id) {
        return store.transaction(()->{
            var row=store.one("SELECT * FROM kb_ingest_task WHERE id=? AND operation IS NOT NULL FOR UPDATE",id);
            if(row==null||!Set.of("QUEUED","PROCESSING","INDEXING").contains(text(row,"status")))return null;
            java.time.Instant instant=store.now();
            Object lease=row.get("lease_until");if(lease instanceof java.sql.Timestamp t&&t.toInstant().isAfter(instant))return null;
            String owner=UUID.randomUUID().toString();long token=number(row,"lease_token")+1;
            store.update("UPDATE kb_ingest_task SET lease_owner=?,lease_token=?,lease_until=?,status='PROCESSING',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                owner,token,java.sql.Timestamp.from(instant.plusSeconds(Math.max(180,properties.getLeaseSeconds()))),id);
            return store.one("SELECT * FROM kb_ingest_task WHERE id=?",id);
        });
    }
    boolean owns(Map<String,Object> job) {
        return store.one("SELECT id FROM kb_ingest_task WHERE id=? AND lease_owner=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP",
            job.get("id"),job.get("lease_owner"),job.get("lease_token"))!=null;
    }
    void renew(Map<String,Object> job) {
        if(!owns(job))throw new IllegalStateException("Lease expired");
        store.update("UPDATE kb_ingest_task SET lease_until=? WHERE id=? AND lease_owner=? AND lease_token=?",
            java.sql.Timestamp.from(store.now().plusSeconds(Math.max(180,properties.getLeaseSeconds()))),job.get("id"),job.get("lease_owner"),job.get("lease_token"));
    }
    void renewForRewrite(Map<String,Object> job) {
        if(!owns(job))throw new IllegalStateException("Lease expired");
        // Markdown makes two sequential model calls. Keep a finite lease long enough for their configured deadlines.
        long seconds=Math.max(Math.max(180,properties.getLeaseSeconds()),2*kb.getProcessor().getLlmTimeoutMs()/1000+60);
        store.update("UPDATE kb_ingest_task SET lease_until=? WHERE id=? AND lease_owner=? AND lease_token=?",
            java.sql.Timestamp.from(store.now().plusSeconds(seconds)),job.get("id"),job.get("lease_owner"),job.get("lease_token"));
    }
    boolean current(Map<String,Object> job) {
        var master=document(text(job,"document_id"),false);
        return master!=null&&number(master,"desired_seq")==number(job,"publish_seq")&&"PUBLISH".equals(text(master,"desired_state"));
    }
    boolean putTextFenced(String doc,Map<String,Object> job,String key,String content) {
        return store.transaction(()->{
            var master=document(doc,true);
            if(master==null||"DELETED".equals(text(master,"desired_state"))) {
                if(job==null)throw new ResponseStatusException(HttpStatus.GONE,"Deleted document");
                return false;
            }
            if(job!=null&&(!owns(job)||("PUBLISH".equals(text(job,"operation"))&&!current(job))))return false;
            objects.putText(key,content);return true;
        });
    }
    void execute(Map<String,Object> job) {
        try {
            String op=text(job,"operation");
            if(op.equals("CLEANUP")){cleanup(job);return;}
            if(op.equals("PUBLISH")&&!current(job)){finish(job,"DISABLED",null);return;}
            var body=payload(job);
            String expectedSource=required(body,"sourceMarkdown");
            if(!putTextFenced(text(job,"document_id"),job,text(job,"raw_object_key"),expectedSource))return;
            String source=new String(objects.get(text(job,"raw_object_key"),2097152),StandardCharsets.UTF_8);
            if(!UnifiedObjectStore.sha(source).equals(body.get("sourceSha256")))throw new IllegalStateException("Source hash changed");
            String mode=String.valueOf(body.getOrDefault("contentType","GUIDE_QA"));
            String finalText=source;
            if(op.equals("PREVIEW")||mode.equals("GUIDE_QA")) {
                if("BLOG".equals(body.get("source"))&&op.equals("PUBLISH")) {
                    checkRewrite(text(job,"document_id"),body,number(job,"source_rev_no"));
                    var rewrite=store.one("SELECT * FROM kb_ingest_task WHERE release_id=? AND operation='PREVIEW'",body.get("rewriteJobId"));
                    var preview=preview(rewrite);finalText="# "+title(source)+"\n\n## Guide\n\n"+preview.get("guideMd")+"\n\n## Q&A\n\n"+preview.get("qaMd");
                } else {
                    String guideKey=UnifiedPaths.derived(text(job,"document_id"),number(job,"source_rev_no"),text(job,"release_id"),"guide");
                    String qaKey=UnifiedPaths.derived(text(job,"document_id"),number(job,"source_rev_no"),text(job,"release_id"),"qa");
                    // READY derivative keys make retries reuse the exact generated bytes.
                    String guide,qa;
                    if(job.get("processed_guide_key")!=null&&job.get("processed_qa_key")!=null) {
                        guide=new String(objects.get(guideKey,4194304),StandardCharsets.UTF_8);qa=new String(objects.get(qaKey,4194304),StandardCharsets.UTF_8);
                    } else {
                        renewForRewrite(job);
                        ProcessResult result;
                        if(body.containsKey("generatedGuide")&&body.containsKey("generatedQa")) {
                            result=new ProcessResult(String.valueOf(body.get("generatedGuide")),String.valueOf(body.get("generatedQa")),"recovered");
                        } else {
                            var processor=router.route(text(job,"source_type"),source);
                            var context=new LinkedHashMap<String,Object>(body);context.remove("sourceMarkdown");
                            result=processor.process(source,text(job,"source_type"),context);
                        }
                        guide=Objects.requireNonNullElse(result.guideContent(),"");qa=Objects.requireNonNullElse(result.qaContent(),"");
                        if(op.equals("PREVIEW")&&(guide.isBlank()||qa.isBlank()))throw new IllegalStateException("Preview needs guide and qa");
                        if(!op.equals("PREVIEW")) {
                            var check=quality.check(source,result,text(job,"source_type"));
                            if(!check.passed())throw new IllegalStateException("Rewrite quality failed");
                        }
                        validateOutput(guide,body);validateOutput(qa,body);
                        // Persist output identity before upload; after a crash retries reuse this JSON and never regenerate same-key content.
                        var outputBody=new LinkedHashMap<String,Object>(body);outputBody.put("generatedGuide",guide);outputBody.put("generatedQa",qa);
                        if(!owns(job))return;
                        if(store.update("UPDATE kb_ingest_task SET payload_json=? WHERE id=? AND lease_owner=? AND lease_token=?",encode(outputBody),job.get("id"),job.get("lease_owner"),job.get("lease_token"))!=1)return;
                        if(!putTextFenced(text(job,"document_id"),job,guideKey,guide)
                            ||!putTextFenced(text(job,"document_id"),job,qaKey,qa))return;
                        store.update("UPDATE kb_ingest_task SET processed_guide_key=?,processed_qa_key=?,processor_version=? WHERE id=? AND lease_owner=? AND lease_token=?",guideKey,qaKey,result.processorVersion(),job.get("id"),job.get("lease_owner"),job.get("lease_token"));
                    }
                    finalText="# "+title(source)+"\n\n## Guide\n\n"+guide+"\n\n## Q&A\n\n"+qa;
                }
            }
            if(op.equals("PREVIEW")) {finish(job,"COMPLETED",null);return;}
            if("CANDIDATE".equals(text(job,"review_status"))) {finish(job,"WAITING_REVIEW",null);return;}
            String doc=text(job,"document_id"),release=text(job,"release_id");
            String key=UnifiedPaths.release(doc,release),hash=UnifiedObjectStore.sha(finalText);
            if(finalText.length()>2_000_000)throw new IllegalStateException("Final index text exceeds 2000000 characters");
            if(!putTextFenced(doc,job,key,finalText)){if(owns(job))finish(job,"DISABLED",null);return;}
            store.update("UPDATE kb_document SET status='INDEXING',object_key=?,sha256=?,updated_at=CURRENT_TIMESTAMP WHERE release_id=?",key,hash,release);
            renew(job);
            var remote=vectors.prepare(Map.of("documentId",doc,"releaseId",release,"sha256",hash,"title",title(source),"content",finalText));
            if(!"READY".equals(remote.get("status")))throw new IllegalStateException("Candidate index not ready");
            String vectorId=required(remote,"vectorDocumentId");
            if(!owns(job))return;
            if(!current(job)){vectors.delete(doc,release);finish(job,"DISABLED",null);return;}
            vectors.enable(doc,release);
            boolean activated=store.transaction(()->{
                var master=document(doc,true);
                if(!owns(job)||number(master,"desired_seq")!=number(job,"publish_seq")||!"PUBLISH".equals(text(master,"desired_state")))return false;
                store.update("UPDATE kb_document SET status='SUPERSEDED',updated_at=CURRENT_TIMESTAMP WHERE document_id=? AND status='EFFECTIVE'",doc);
                store.update("UPDATE kb_document SET status='EFFECTIVE',ragflow_document_id=?,dataset_name=?,object_key=?,sha256=?,updated_at=CURRENT_TIMESTAMP WHERE release_id=?",vectorId,"kb-unified",key,hash,release);
                store.update("UPDATE kb_logical_document SET effective_release_id=?,updated_at=CURRENT_TIMESTAMP WHERE document_id=?",release,doc);
                store.update("UPDATE kb_ingest_task SET external_document_id=?,external_job_id=? WHERE id=?",vectorId,remote.get("jobId"),job.get("id"));
                finish(job,"COMPLETED",null);
                createJob(doc,UUID.randomUUID().toString(),"CLEANUP",number(job,"source_rev_no"),number(job,"publish_seq"),
                    Map.of("delete",false),"after-publish:"+doc+":"+job.get("publish_seq"),"MARKDOWN","APPROVED",null);
                return true;
            });
            if(!activated&&owns(job)&&!current(job)){vectors.delete(doc,release);finish(job,"DISABLED",null);}
        } catch(Exception ex) {failure(job,ex);}
    }
    @SuppressWarnings("unchecked")
    void validateOutput(String output,Map<String,Object> body) {
        if(output.getBytes(StandardCharsets.UTF_8).length>4194304||EXTERNAL_IMAGE.matcher(output).find())throw new IllegalStateException("Invalid rewrite images or length");
        Set<String> allowed=new HashSet<>();
        for(Object id:(List<Object>)body.getOrDefault("allowedAttachmentIds",List.of()))allowed.add(uuid(id.toString()));
        var matcher=ATTACHMENT.matcher(output);while(matcher.find())if(!allowed.contains(uuid(matcher.group(1))))throw new IllegalStateException("Unregistered attachment reference");
    }
    void finish(Map<String,Object> job,String status,String error) {
        store.update("UPDATE kb_ingest_task SET status=?,error_message=?,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner=? AND lease_token=?",
            status,error,job.get("id"),job.get("lease_owner"),job.get("lease_token"));
    }
    void failure(Map<String,Object> job,Exception ex) {
        if(!owns(job))return;
        long attempt=number(job,"attempts");
        boolean waiting=ex instanceof UnifiedVectorClient.IndexPendingException;
        boolean terminal=!waiting&&attempt>=properties.getMaxAttempts()&&!text(job,"operation").equals("CLEANUP");
        String error=Objects.requireNonNullElse(ex.getMessage(),"Integration failure");
        store.update("UPDATE kb_ingest_task SET status=?,attempts=attempts-?,error_code='INTEGRATION_FAILED',error_message=?,retryable=TRUE,next_run_at=?,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_owner=? AND lease_token=?",
            terminal?"FAILED":"QUEUED",waiting?1:0,error.substring(0,Math.min(error.length(),1000)),
            java.sql.Timestamp.from(store.now().plusSeconds(waiting?30:Math.min(3600,5L<<Math.min(attempt,9)))),job.get("id"),job.get("lease_owner"),job.get("lease_token"));
        if(terminal&&text(job,"operation").equals("PUBLISH")) {
            store.update("UPDATE kb_document SET status='FAILED' WHERE release_id=? AND status IN ('PREPARING','INDEXING')",job.get("release_id"));
            createJob(text(job,"document_id"),UUID.randomUUID().toString(),"CLEANUP",number(job,"source_rev_no"),number(job,"publish_seq"),
                Map.of("delete",false),"failed-publish:"+job.get("document_id")+":"+job.get("publish_seq"),"MARKDOWN","APPROVED",null);
        }
    }
    void cleanup(Map<String,Object> job) {
        String doc=text(job,"document_id");
        var master=document(doc,false);
        if(master==null){finish(job,"COMPLETED",null);return;}
        var releases=store.rows("SELECT * FROM kb_document WHERE document_id=?",doc);
        for(var release:releases) {
            var latest=document(doc,false);
            if(Objects.equals(latest.get("effective_release_id"),release.get("release_id")))continue;
            if(number(release,"publish_seq")==number(latest,"desired_seq")&&"PUBLISH".equals(text(latest,"desired_state"))&&Set.of("PREPARING","INDEXING").contains(text(release,"status")))continue;
            renew(job);
            vectors.delete(doc,text(release,"release_id"));
            store.update("UPDATE kb_document SET status='DELETED',ragflow_document_id=NULL WHERE release_id=? AND status<>'EFFECTIVE'",release.get("release_id"));
        }
        store.transaction(()->{
            var locked=document(doc,true);
            if("DELETED".equals(text(locked,"desired_state"))) {
            // Wait for all superseded workers to release or expire before a final prefix cleanup.
            var running=store.one("SELECT id FROM kb_ingest_task WHERE document_id=? AND operation<>'CLEANUP' AND lease_until>CURRENT_TIMESTAMP LIMIT 1",doc);
            if(running!=null)throw new IllegalStateException("Old document worker still running");
            objects.deleteDocument(doc);
            store.update("UPDATE kb_ingest_task SET payload_json=NULL,raw_object_key=NULL,processed_guide_key=NULL,processed_qa_key=NULL,status='DISABLED' WHERE document_id=? AND operation<>'CLEANUP'",doc);
            store.update("UPDATE kb_document SET object_key=NULL,metadata_json=NULL,title='Deleted',sha256=NULL,status='DELETED' WHERE document_id=?",doc);
            }
            return null;
        });
        store.transaction(()->{
            var latest=document(doc,true);
            if(number(latest,"desired_seq")==number(job,"publish_seq"))store.update("UPDATE kb_logical_document SET cleanup_complete=TRUE WHERE document_id=?",doc);
            finish(job,"COMPLETED",null);return null;
        });
    }
}
