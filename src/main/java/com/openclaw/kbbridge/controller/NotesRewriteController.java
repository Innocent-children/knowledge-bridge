package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.dto.notes.NotesRewriteRequest;
import com.openclaw.kbbridge.service.NotesRewriteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class NotesRewriteController {
    private final NotesRewriteService service;
    public NotesRewriteController(NotesRewriteService service){this.service=service;}
    @PostMapping("/api/v1/notes/rewrite")
    public Map<String,Object> rewrite(@Valid @RequestBody NotesRewriteRequest request){return service.rewrite(request);}
}
