package com.openclaw.kbbridge.unified;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ingest.IngestRequest;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.exception.GlobalExceptionHandler;
import com.openclaw.kbbridge.security.BlogServiceAuthFilter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.openclaw.kbbridge.processor.*;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.router.IngestRouter;
import com.openclaw.kbbridge.service.FileTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.openclaw.kbbridge.unified.UnifiedRepository.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transactional state tests. All storage/index/processor effects use explicit, network-free substitutes. */
class UnifiedKnowledgeServiceTest {
    private JdbcTemplate db;
    private UnifiedRepository store;
    private UnifiedKnowledgeService service;
    private UnifiedProperties properties;
    private ObjectsFixture objects;
    private VectorsFixture vectors;
    private final AtomicInteger processorCalls = new AtomicInteger();
    private ProcessResult generated;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("unified/schema-h2.sql")).execute(ds);
        db = new JdbcTemplate(ds);
        store = new UnifiedRepository(db, new DataSourceTransactionManager(ds));
        properties = new UnifiedProperties();
        properties.setMaxAttempts(1);
        objects = new ObjectsFixture();
        vectors = new VectorsFixture();
        processorCalls.set(0);
        generated = new ProcessResult("# Guide\n---CHUNK---\nStep one", "## Q1\n**问题**：One?\n**回答**：One.\n"
                + "## Q2\n**问题**：Two?\n**回答**：Two.\n## Q3\n**问题**：Three?\n**回答**：Three.", "test-v1");
        KnowledgeProcessor processor = (raw, type, context) -> { processorCalls.incrementAndGet(); return generated; };
        var router = new IngestRouter(Map.of("markdownKnowledgeProcessor", processor,
                "feishuQaProcessor", processor, "attachmentProcessor", processor));
        KbProperties kb = new KbProperties();
        IngestTaskMapper mapper = mock(IngestTaskMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(call -> db.queryForObject("SELECT * FROM kb_ingest_task WHERE id=?",
                new BeanPropertyRowMapper<>(IngestTaskEntity.class), call.getArgument(0)));
        service = new UnifiedKnowledgeService(store, objects.client, vectors.client, properties, new ObjectMapper(),
                router, new QualityChecker(kb), new FileTextExtractor(), mapper, kb);
    }

    @Test void workerProcessesBothEntrypointsWithDefaultConfiguration() {
        String blogDocument = id(), blogRelease = id();
        service.publish(blogDocument, publication(blogRelease, 1, 1, "# Blog publication", "SOURCE"));
        var plugin = service.legacyIngest(legacy("default-worker-plugin", "message", "# Plugin knowledge\nDeployment steps."));
        db.update("UPDATE kb_ingest_task SET next_run_at=? WHERE id IN (?,?)",
                Timestamp.from(Instant.now().minusSeconds(1)), job(blogRelease), plugin.taskId());

        new UnifiedWorker(service).tick();

        assertEquals("COMPLETED", task(job(blogRelease)).get("status"));
        assertEquals(blogRelease, master(blogDocument).get("effective_release_id"));
        var pluginTask = task(plugin.taskId());
        assertEquals("COMPLETED", pluginTask.get("status"));
        assertEquals(pluginTask.get("release_id"), master(text(pluginTask, "document_id")).get("effective_release_id"));
        assertEquals(Set.of("BLOG", "OPENCLAW"), items(service.query("knowledge", 5)).stream()
                .map(item -> text(item, "source")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, service.legacyQuery("knowledge", 5).size());
    }

    @Test void blogHttpContractUsesIndependentTokenAndSingleDocumentLifecycle() throws Exception {
        var mapper = new ObjectMapper();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UnifiedController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new BlogServiceAuthFilter("test-token", mapper)).build();
        String doc = id(), release = id();
        String body = mapper.writeValueAsString(publication(release, 1, 1, "# Blog HTTP publication", "SOURCE"));
        mvc.perform(post("/api/v1/blog/documents/{document}/publish", doc)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/blog/documents/{document}/publish", doc)
                .header("Authorization", "Bearer wrong-token")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertEquals(0, count("SELECT COUNT(*) FROM kb_logical_document"));
        mvc.perform(post("/api/v1/blog/documents/{document}/publish", doc)
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.spaceId").doesNotExist());
        service.runSpecific(job(release));
        mvc.perform(get("/api/v1/blog/documents/{document}/status", doc)
                .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.effectivePublicationId").value(release));
        mvc.perform(post("/api/v1/blog/query").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"blog\",\"limit\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].source").value("BLOG"))
                .andExpect(jsonPath("$.items[0].documentId").value(doc));
        mvc.perform(post("/api/v1/blog/documents/{document}/withdraw", doc)
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(withdrawal(2, false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        mvc.perform(post("/api/v1/blog/query").header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"blog\",\"limit\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        var mappings = mvc.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        assertTrue(mappings.getHandlerMethods().keySet().stream().flatMap(mapping -> mapping.getPatternValues().stream())
                .noneMatch(path -> path.contains("spaces") || path.contains("provision")), "Single-admin API has no space provision route");
    }

    @Test void sourcePublicationUsesOriginalBytesAndOnlyBecomesQueryableWhenEffective() {
        String doc = id(), release = id(), source = "# Original note\nKeep these bytes exactly.";
        var body = publication(release, 1, 1, source, "SOURCE");
        assertEquals(true, service.publish(doc, body).get("accepted"));
        assertNull(master(doc).get("effective_release_id"));
        assertEquals(0, processorCalls.get());
        assertTrue(items(service.query("note", 5)).isEmpty());
        service.runSpecific(job(release));
        assertEquals("COMPLETED", task(job(release)).get("status"));
        assertEquals(release, master(doc).get("effective_release_id"));
        assertEquals("EFFECTIVE", release(release).get("status"));
        assertEquals(0, processorCalls.get());
        assertEquals(source, vectors.prepared.get(release).get("content"));
        assertEquals(source, objects.text(text(release(release), "object_key")));
        assertTrue(objects.bytes.keySet().stream().allMatch(key -> key.startsWith("documents/" + doc + "/")));
        assertEquals(List.of(release), items(service.query("note", 5)).stream().map(item -> item.get("publicationId")).toList());
    }

    @Test void rewritePreviewNeedsMatchingExplicitPublicationAndDraftEditsPreserveEffectiveVersion() {
        String doc = id(), rewrite = id(), first = "# First source\nThis is a draft preview.";
        var preview = service.rewrite(rewrite(rewrite, doc, 1, first));
        assertEquals(generated.guideContent(), preview.get("guideMd"));
        assertEquals(1, processorCalls.get());
        assertTrue(vectors.prepared.isEmpty());
        assertNull(master(doc).get("effective_release_id"));
        String release = id();
        var unconfirmed = publication(release, 1, 1, first, "GUIDE_QA");
        assertStatus(422, () -> service.publish(doc, unconfirmed));
        var wrong = publication(id(), 1, 1, first, "GUIDE_QA"); wrong.put("rewriteJobId", id());
        assertStatus(409, () -> service.publish(doc, wrong));
        var mismatch = publication(id(), 1, 2, "# New source\nChanged draft", "GUIDE_QA"); mismatch.put("rewriteJobId", rewrite);
        assertStatus(409, () -> service.publish(doc, mismatch));
        var confirmed = publication(release, 1, 1, first, "GUIDE_QA"); confirmed.put("rewriteJobId", rewrite);
        service.publish(doc, confirmed); service.runSpecific(job(release));
        assertEquals(release, master(doc).get("effective_release_id"));
        assertTrue(String.valueOf(vectors.prepared.get(release).get("content")).contains(generated.qaContent()));
        assertEquals(1, processorCalls.get(), "Publishing a confirmed preview must reuse its exact bytes");
        service.rewrite(rewrite(id(), doc, 3, "# Third draft\nEditing is not publishing"));
        assertEquals(release, master(doc).get("effective_release_id"));
        assertEquals(List.of(release), items(service.query("draft", 5)).stream().map(item -> item.get("publicationId")).toList());
    }

    @Test void draftWithdrawalAndDeletionRegisterDurableSequenceTombstones() {
        String withdrawn = id();
        service.withdraw(withdrawn, withdrawal(5, false));
        assertEquals("WITHDRAWN", master(withdrawn).get("desired_state"));
        service.publish(withdrawn, publication(id(), 4, 1, "# Late source", "SOURCE"));
        assertEquals(5, number(master(withdrawn), "desired_seq"));
        assertEquals(0, count("SELECT COUNT(*) FROM kb_document WHERE document_id=?", withdrawn));
        String deleted = id();
        service.withdraw(deleted, withdrawal(2, true));
        drainCleanup();
        assertEquals("DELETED", master(deleted).get("desired_state"));
        assertTrue(flag(master(deleted), "cleanup_complete"));
        assertStatus(410, () -> service.publish(deleted, publication(id(), 3, 1, "# Cannot resurrect", "SOURCE")));
        assertStatus(410, () -> service.rewrite(rewrite(id(), deleted, 1, "# Cannot recreate")));
        assertTrue(objects.bytes.keySet().stream().noneMatch(key -> key.startsWith("documents/" + deleted + "/")));
    }

    @Test void failedNewVersionPreservesOldEffectiveReleaseAndSuccessfulReplacementCleansOnlyOldVersions() {
        String doc = id(), first = id();
        publishAndRun(doc, first, 1, 1, "# Good first");
        String failed = id();
        service.publish(doc, publication(failed, 2, 2, "# Failed second", "SOURCE"));
        assertEquals(first, master(doc).get("effective_release_id"));
        vectors.failPrepare = true; service.runSpecific(job(failed));
        assertEquals("FAILED", task(job(failed)).get("status"));
        assertEquals(first, master(doc).get("effective_release_id"));
        assertEquals(List.of(first), items(service.query("first", 5)).stream().map(item -> item.get("publicationId")).toList());
        String third = id(); publishAndRun(doc, third, 3, 3, "# Good third");
        assertEquals(third, master(doc).get("effective_release_id"));
        assertEquals(List.of(third), items(service.query("third", 5)).stream().map(item -> item.get("publicationId")).toList());
        service.publish(doc, publication(failed, 2, 2, "# Failed second", "SOURCE"));
        assertEquals(third, master(doc).get("effective_release_id"));
        assertEquals(3, number(master(doc), "desired_seq"));
        drainCleanup();
        assertEquals(Set.of(third), vectors.enabled);
        assertTrue(vectors.deleted.contains(first));
        assertFalse(vectors.deleted.contains(third));
    }

    @Test void withdrawalDuringIndexPreparationRejectsLateResultImmediately() {
        String doc = id(), release = id();
        service.publish(doc, publication(release, 1, 1, "# Late prepared version", "SOURCE"));
        vectors.afterPrepare = () -> service.withdraw(doc, withdrawal(2, false));
        service.runSpecific(job(release));
        assertNull(master(doc).get("effective_release_id"));
        assertEquals("WITHDRAWN", master(doc).get("desired_state"));
        assertFalse(vectors.enabled.contains(release));
        vectors.forcedHits = List.of(vectors.hit(release));
        assertTrue(items(service.query("late", 5)).isEmpty(), "Relational effective state must reject stale vector hits");
        service.publish(doc, publication(id(), 1, 1, "# Late prepared version", "SOURCE"));
        assertEquals(2, number(master(doc), "desired_seq"));
        assertNull(master(doc).get("effective_release_id"));
    }

    @Test void candidateReviewReusesDerivativesAndRejectionKeepsTheOldEffectiveVersion() {
        var first = service.legacyCandidate(legacy("candidate-1", "message", "# Candidate one\nConfiguration steps are documented here."));
        service.runSpecific(first.taskId());
        assertEquals("WAITING_REVIEW", task(first.taskId()).get("status"));
        String doc = text(task(first.taskId()), "document_id"), firstRelease = text(task(first.taskId()), "release_id");
        assertNull(master(doc).get("effective_release_id"));
        assertTrue(vectors.prepared.isEmpty());
        assertEquals(1, processorCalls.get());
        service.legacyReview(first.taskId(), true); service.runSpecific(first.taskId());
        assertEquals(firstRelease, master(doc).get("effective_release_id"));
        assertEquals(1, processorCalls.get());
        var second = service.legacyCandidate(legacy("candidate-2", "message", "# Candidate two\nA changed configuration draft is being reviewed."));
        service.runSpecific(second.taskId()); service.legacyReview(second.taskId(), false);
        assertEquals("REJECTED", task(second.taskId()).get("review_status"));
        assertEquals(firstRelease, master(doc).get("effective_release_id"));
        assertEquals(List.of(firstRelease), items(service.query("configuration", 5)).stream().map(item -> item.get("publicationId")).toList());
    }

    @Test void processingCandidateCannotBeApprovedOrRejectedBeforeDerivativesAreReady() {
        var candidate = service.legacyCandidate(legacy("early-review", "review-message", "# Candidate awaiting processing"));
        assertStatus(409, () -> service.legacyReview(candidate.taskId(), true));
        assertStatus(409, () -> service.legacyReview(candidate.taskId(), false));
        assertEquals("QUEUED", task(candidate.taskId()).get("status"));
        assertEquals("CANDIDATE", task(candidate.taskId()).get("review_status"));
        service.runSpecific(candidate.taskId());
        assertEquals("WAITING_REVIEW", task(candidate.taskId()).get("status"));
        assertEquals("CANDIDATE", task(candidate.taskId()).get("review_status"));
        assertTrue(vectors.prepared.isEmpty());
    }

    @Test void liveIndexLeaseWaitingDoesNotExhaustFailureRetries() {
        String doc = id(), release = id();
        service.publish(doc, publication(release, 1, 1, "# Index lease still active", "SOURCE"));
        long taskId = job(release);
        vectors.indexPending = true;
        for (int i = 0; i < properties.getMaxAttempts() + 3; i++) {
            Instant before = Instant.now();
            service.runSpecific(taskId);
            var row = task(taskId);
            assertEquals("QUEUED", row.get("status"));
            assertEquals(0, number(row, "attempts"));
            assertTrue(((Timestamp) row.get("next_run_at")).toInstant().isAfter(before.plusSeconds(28)));
            db.update("UPDATE kb_ingest_task SET next_run_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), taskId);
        }
        assertNull(master(doc).get("effective_release_id"));
        assertEquals("INDEXING", release(release).get("status"));
        vectors.indexPending = false;
        service.runSpecific(taskId);
        assertEquals("COMPLETED", task(taskId).get("status"));
        assertEquals(release, master(doc).get("effective_release_id"));
        assertTrue(vectors.enabled.contains(release));
    }

    @Test void consoleDisableAndEnableUseANewPublicationSequenceAndRelease() {
        var response = service.legacyIngest(legacy("manual", "message", "# Shared administrator knowledge\nSteps for deployment."));
        service.runSpecific(response.taskId());
        var firstTask = task(response.taskId());
        String doc = text(firstTask, "document_id"), first = text(firstTask, "release_id");
        long oldRow = number(release(first), "id");
        service.legacyDocumentToggle(oldRow, false);
        assertNull(master(doc).get("effective_release_id"));
        assertTrue(items(service.query("deployment", 5)).isEmpty());
        long disabledSeq = number(master(doc), "desired_seq");
        drainCleanup();
        service.legacyDocumentToggle(oldRow, true);
        var publish = store.one("SELECT * FROM kb_ingest_task WHERE document_id=? AND operation='PUBLISH' ORDER BY publish_seq DESC LIMIT 1", doc);
        String next = text(publish, "release_id");
        assertNotEquals(first, next);
        assertTrue(number(publish, "publish_seq") > disabledSeq);
        long pendingSeq=number(master(doc),"desired_seq");
        service.legacyDocumentToggle(oldRow,true);
        assertEquals(pendingSeq,number(master(doc),"desired_seq"));
        service.runSpecific(number(publish, "id")); drainCleanup();
        assertEquals(next, master(doc).get("effective_release_id"));
        assertEquals(Set.of(next), vectors.enabled);
        service.legacyDocumentToggle(oldRow,true);
        var restored=store.one("SELECT * FROM kb_ingest_task WHERE document_id=? AND operation='PUBLISH' ORDER BY publish_seq DESC LIMIT 1",doc);
        assertTrue(number(restored,"publish_seq")>pendingSeq);
        service.runSpecific(number(restored,"id"));drainCleanup();
        assertEquals(restored.get("release_id"),master(doc).get("effective_release_id"));
        assertEquals(Set.of(text(restored,"release_id")),vectors.enabled);
    }

    @Test void consoleCannotCreateBlogPublicationSequencesUnknownToTheBlogDatabase() {
        String doc=id(),release=id();publishAndRun(doc,release,1,1,"# Blog-owned publication");
        long row=number(release(release),"id");
        assertStatus(409,()->service.legacyDocumentToggle(row,false));
        assertStatus(409,()->service.legacyDocumentToggle(row,true));
        assertEquals(1,number(master(doc),"desired_seq"));
        assertEquals(release,master(doc).get("effective_release_id"));
        assertEquals(Set.of(release),vectors.enabled);
    }

    @Test void duplicatePublicationIsIdempotentAndReusingASequenceWithDifferentReleaseIsRejected() {
        String doc = id(), release = id(); var body = publication(release, 1, 1, "# Repeatable", "SOURCE");
        service.publish(doc, body); long before = count("SELECT COUNT(*) FROM kb_ingest_task");
        service.publish(doc, body);
        assertEquals(before, count("SELECT COUNT(*) FROM kb_ingest_task"));
        assertEquals(1, count("SELECT COUNT(*) FROM kb_document WHERE document_id=?", doc));
        assertStatus(409, () -> service.publish(doc, publication(id(), 1, 1, "# Repeatable", "SOURCE")));
        service.runSpecific(job(release));
        service.publish(doc, body);
        assertEquals(release, master(doc).get("effective_release_id"));
    }

    @Test void queryRejectsIncorrectHashesAndNonEffectiveReleases() {
        String doc = id(), release = id(); publishAndRun(doc, release, 1, 1, "# Verified content");
        var corrupted = new LinkedHashMap<String, Object>(vectors.hit(release)); corrupted.put("sha256", "wrong");
        vectors.forcedHits = List.of(corrupted);
        assertTrue(items(service.query("verified", 5)).isEmpty());
        vectors.forcedHits = List.of(vectors.hit(release));
        assertEquals(1, items(service.query("verified", 5)).size());
        service.withdraw(doc, withdrawal(2, false));
        assertTrue(items(service.query("verified", 5)).isEmpty());
    }

    @Test void losingLeaseAfterPrepareCannotDeleteTheNewLeaseWinnersSameRelease() {
        assertLeaseWinnerSurvives(false);
    }
    @Test void losingLeaseDuringEnableCannotDeleteTheNewLeaseWinnersSameRelease() {
        assertLeaseWinnerSurvives(true);
    }
    private void assertLeaseWinnerSurvives(boolean duringEnable) {
        String doc = id(), release = id(); service.publish(doc, publication(release, 1, 1, "# Lease handover", "SOURCE"));
        long id = job(release); var oldClaim = service.claim(id);
        assertNotNull(oldClaim);
        Runnable win = () -> {
            db.update("UPDATE kb_ingest_task SET lease_until=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), id);
            var nextClaim = service.claim(id); assertNotNull(nextClaim);
            assertTrue(number(nextClaim, "lease_token") > number(oldClaim, "lease_token"));
            service.execute(nextClaim);
            assertEquals("COMPLETED", task(id).get("status"));
        };
        if (duringEnable) vectors.afterEnable = win; else vectors.afterPrepare = win;
        service.execute(oldClaim);
        assertEquals(release, master(doc).get("effective_release_id"));
        assertEquals("COMPLETED", task(id).get("status"));
        assertTrue(vectors.enabled.contains(release));
        assertFalse(vectors.deleted.contains(release), "A stale lease must never delete the current lease's same release");
        assertEquals(1, items(service.query("lease", 5)).size());
    }

    @Test void deletionWaitsForOldLeaseThenRemovesObjectsWithoutResurrection() {
        String doc = id(), release = id(); service.publish(doc, publication(release, 1, 1, "# Deleted while preparing", "SOURCE"));
        vectors.afterPrepare = () -> { service.withdraw(doc, withdrawal(2, true)); drainCleanup(); };
        service.runSpecific(job(release));
        drainCleanup();
        assertEquals("DELETED", master(doc).get("desired_state"));
        assertTrue(flag(master(doc), "cleanup_complete"));
        assertTrue(objects.bytes.keySet().stream().noneMatch(key -> key.startsWith("documents/" + doc + "/")));
        assertFalse(vectors.enabled.contains(release));
        assertStatus(410, () -> service.publish(doc, publication(id(), 3, 2, "# Resurrected", "SOURCE")));
    }

    private String id() { return UUID.randomUUID().toString(); }
    private Map<String, Object> publication(String release, long seq, long revision, String source, String type) {
        var body = new LinkedHashMap<String, Object>(); body.put("publicationId", release); body.put("publishSeq", seq);
        body.put("sourceRevNo", revision); body.put("sourceMarkdown", source); body.put("sourceSha256", UnifiedObjectStore.sha(source));
        body.put("contentType", type); body.put("allowedAttachmentIds", List.of()); return body;
    }
    private Map<String, Object> rewrite(String rewrite, String doc, long revision, String source) {
        var body = new LinkedHashMap<String, Object>(); body.put("noteId", doc); body.put("rewriteJobId", rewrite);
        body.put("sourceRevNo", revision); body.put("sourceMarkdown", source); body.put("sourceSha256", UnifiedObjectStore.sha(source));
        body.put("allowedAttachmentIds", List.of()); return body;
    }
    private Map<String, Object> withdrawal(long seq, boolean delete) { return Map.of("publishSeq", seq, "delete", delete); }
    private IngestRequest legacy(String request, String message, String source) {
        return new IngestRequest(request, "original-openclaw-user", null, List.of(message), source, "MARKDOWN", null, false);
    }
    private void publishAndRun(String doc, String release, long seq, long revision, String source) {
        service.publish(doc, publication(release, seq, revision, source, "SOURCE")); service.runSpecific(job(release));
        assertEquals(release, master(doc).get("effective_release_id"));
    }
    private Map<String, Object> master(String doc) { return store.one("SELECT * FROM kb_logical_document WHERE document_id=?", doc); }
    private Map<String, Object> release(String release) { return store.one("SELECT * FROM kb_document WHERE release_id=?", release); }
    private Map<String, Object> task(long id) { return store.one("SELECT * FROM kb_ingest_task WHERE id=?", id); }
    private long job(String release) { return number(store.one("SELECT * FROM kb_ingest_task WHERE release_id=? AND operation='PUBLISH'", release), "id"); }
    private long count(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> items(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("items");
    }
    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
    private void drainCleanup() {
        for (var pending : store.rows("SELECT * FROM kb_ingest_task WHERE operation='CLEANUP' AND status='QUEUED' ORDER BY id")) {
            db.update("UPDATE kb_ingest_task SET next_run_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), pending.get("id"));
            service.runSpecific(number(pending, "id"));
        }
    }

    private static final class ObjectsFixture {
        final Map<String, byte[]> bytes = new ConcurrentHashMap<>();
        final UnifiedObjectStore client = mock(UnifiedObjectStore.class, call -> {
            String method = call.getMethod().getName(); Object[] args = call.getArguments();
            if (method.equals("putText") || method.equals("put")) {
                String key = String.valueOf(args[0]);
                assertTrue(key.startsWith("documents/"), "Every new object belongs to the unified document prefix");
                assertFalse(key.contains(".."));
                byte[] content = method.equals("putText") ? String.valueOf(args[1]).getBytes(StandardCharsets.UTF_8) : ((byte[]) args[1]).clone();
                byte[] prior = bytes.putIfAbsent(key, content);
                if (prior != null && !Arrays.equals(prior, content)) throw new IllegalStateException("Immutable object conflict");
                return null;
            }
            if (method.equals("get")) {
                byte[] found = bytes.get(String.valueOf(args[0]));
                if (found == null) throw new IllegalStateException("Object not found: " + args[0]);
                if (found.length > ((Number) args[1]).intValue()) throw new IllegalArgumentException("Object exceeds limit");
                return found.clone();
            }
            if (method.equals("deleteDocument")) {
                String doc = String.valueOf(args[args.length - 1]);
                bytes.keySet().removeIf(key -> key.startsWith("documents/" + doc + "/")); return null;
            }
            if (method.equals("toString")) return "Network-free immutable object fixture";
            throw new UnsupportedOperationException("Unexpected object operation: " + method);
        });
        String text(String key) { return new String(bytes.get(key), StandardCharsets.UTF_8); }
    }
    private static final class VectorsFixture {
        final Map<String, Map<String, Object>> prepared = new LinkedHashMap<>();
        final Set<String> enabled = new LinkedHashSet<>(), deleted = new LinkedHashSet<>();
        List<Map<String, Object>> forcedHits;
        Runnable afterPrepare, afterEnable;
        boolean failPrepare, indexPending;
        final UnifiedVectorClient client = mock(UnifiedVectorClient.class, call -> {
            String method = call.getMethod().getName(); Object[] args = call.getArguments();
            if (method.equals("provision")) return Map.of("datasetId", "test-unified-dataset");
            if (method.equals("prepare")) {
                if (indexPending) throw new UnifiedVectorClient.IndexPendingException();
                if (failPrepare) { failPrepare = false; throw new IllegalStateException("Indexing failed in test fixture"); }
                @SuppressWarnings("unchecked") Map<String, Object> input = (Map<String, Object>) args[0];
                String release = String.valueOf(input.get("releaseId"));
                var prior = prepared.putIfAbsent(release, new LinkedHashMap<>(input));
                if (prior != null && !prior.equals(input)) throw new IllegalStateException("Immutable vector release changed");
                Runnable event = afterPrepare; afterPrepare = null; if (event != null) event.run();
                return Map.of("status", "READY", "vectorDocumentId", "vector-" + release, "jobId", release);
            }
            if (method.equals("enable")) {
                String release = String.valueOf(args[args.length - 1]); enabled.add(release);
                Runnable event = afterEnable; afterEnable = null; if (event != null) event.run(); return null;
            }
            if (method.equals("delete")) {
                String release = String.valueOf(args[args.length - 1]); deleted.add(release); enabled.remove(release); return null;
            }
            if (method.equals("query")) return Map.of("items", forcedHits != null ? forcedHits : enabled.stream().map(this::hit).toList());
            if (method.equals("toString")) return "Network-free vector lifecycle fixture";
            throw new UnsupportedOperationException("Unexpected vector operation: " + method);
        });
        Map<String, Object> hit(String release) {
            var row = new LinkedHashMap<String, Object>(prepared.get(release));
            row.put("vectorDocumentId", "vector-" + release); row.put("score", .9); return row;
        }
    }
}
