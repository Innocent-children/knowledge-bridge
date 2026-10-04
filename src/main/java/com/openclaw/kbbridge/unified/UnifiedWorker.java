package com.openclaw.kbbridge.unified;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.openclaw.kbbridge.unified.UnifiedRepository.*;
@Component
public class UnifiedWorker {
    private static final Logger log=LoggerFactory.getLogger(UnifiedWorker.class);
    private final UnifiedKnowledgeService service;
    public UnifiedWorker(UnifiedKnowledgeService service) {this.service=service;}
    @Scheduled(fixedDelay=1000)
    public void tick() {
        if(!service.properties.isWorkerEnabled())return;
        try {
            var rows=service.store.rows("SELECT id FROM kb_ingest_task WHERE operation IS NOT NULL AND status IN ('QUEUED','PROCESSING','INDEXING') AND next_run_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP) ORDER BY CASE WHEN operation='CLEANUP' THEN 0 ELSE 1 END,id LIMIT 4");
            for(var row:rows)service.runSpecific(number(row,"id"));
        } catch(Exception ex) {log.warn("Unified jobs unavailable; check bridge schema and service configuration: {}",ex.getClass().getSimpleName());}
    }
}
