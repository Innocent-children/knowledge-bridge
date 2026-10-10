package com.openclaw.kbbridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class ProcessingController {
    private final ProcessingService service;
    private final JdbcTemplate db;
    private final PreviewService previews;
    public ProcessingController(ProcessingService service,JdbcTemplate db,PreviewService previews) {this.service=service;this.db=db;this.previews=previews;}
    @GetMapping("/health") public Map<String,Object> health() {db.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP");}
    @PostMapping("/internal/rewrite") public Map<String,Object> rewrite(@RequestBody Map<String,Object> body) {return service.rewrite(body);}
    @PostMapping("/internal/documents/{document}/publish") public Map<String,Object> publish(@PathVariable String document,@RequestBody Map<String,Object> body) {return service.publish(document,body);}
    @PostMapping("/internal/documents/{document}/withdraw") public Map<String,Object> withdraw(@PathVariable String document,@RequestBody Map<String,Object> body) {return service.withdraw(document,body);}
    @GetMapping("/internal/documents/{document}/status") public Map<String,Object> status(@PathVariable String document) {return service.status(document);}
    @GetMapping("/internal/documents/{document}/previews/{rewrite}") public Map<String,Object> preview(@PathVariable String document,@PathVariable String rewrite) {return previews.snapshot(document,rewrite);}
    @PostMapping("/internal/documents/{document}/previews/{rewrite}/cancel") public Map<String,Object> cancel(@PathVariable String document,@PathVariable String rewrite) {previews.cancel(document,rewrite);return Map.of("status","CANCELLED");}
    @PostMapping("/internal/query") public Map<String,Object> query(@RequestBody Query body) {return service.query(body.query(),body.limit()==null?10:body.limit(),Boolean.TRUE.equals(body.debug()),body.mode()==null?"enhanced":body.mode(),body.allowedReleaseIds());}
    public record Query(String query,Integer limit,Boolean debug,String mode,java.util.List<String> allowedReleaseIds) {}
}
