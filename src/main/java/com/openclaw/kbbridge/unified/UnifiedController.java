package com.openclaw.kbbridge.unified;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/blog")
public class UnifiedController {
    private final UnifiedKnowledgeService service;
    public UnifiedController(UnifiedKnowledgeService service) {this.service=service;}
    @PostMapping("/rewrite")
    public Map<String,Object> rewrite(@RequestBody Map<String,Object> body) {return service.rewrite(body);}
    @PostMapping("/documents/{document}/publish")
    public Map<String,Object> publish(@PathVariable String document,@RequestBody Map<String,Object> body) {return service.publish(document,body);}
    @PostMapping("/documents/{document}/withdraw")
    public Map<String,Object> withdraw(@PathVariable String document,@RequestBody Map<String,Object> body) {return service.withdraw(document,body);}
    @GetMapping("/documents/{document}/status")
    public Map<String,Object> status(@PathVariable String document) {return service.status(document);}
    @PostMapping("/query")
    public Map<String,Object> query(@RequestBody Map<String,Object> body) {
        Object value=body.getOrDefault("limit",10);
        if(!(value instanceof Number limit))throw new IllegalArgumentException("Invalid limit");
        return service.query(UnifiedKnowledgeService.required(body,"query"),limit.intValue());
    }
}
