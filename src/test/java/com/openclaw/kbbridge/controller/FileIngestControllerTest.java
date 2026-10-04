package com.openclaw.kbbridge.controller;

import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.dto.ingest.FileIngestResponse;
import com.openclaw.kbbridge.service.FileIngestService;
import com.openclaw.kbbridge.unified.UnifiedKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileIngestControllerTest {
    private MockMvc mvc;
    private UnifiedKnowledgeService unified;
    private FileIngestService old;
    @BeforeEach void setup() {
        KbProperties props = new KbProperties();
        props.getSecurity().setSharedSecret("old-secret");
        old = mock(FileIngestService.class);
        unified = mock(UnifiedKnowledgeService.class);
        when(unified.legacyFile(anyString(), anyString(), nullable(String.class), anyString(), nullable(String.class),
                anyBoolean(), any())).thenReturn(new FileIngestResponse("req", 1L, "QUEUED", false));
        FileIngestController controller = new FileIngestController(old, props);
        controller.setFileToken("file-token");
        controller.setUnifiedKnowledgeService(unified);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void fileIngestTokenPreservesOriginalMultipartContract() throws Exception {
        mvc.perform(upload().header("X-KB-File-Token", "file-token")).andExpect(status().isOk());
        verify(unified).legacyFile(eq("req"), eq("reported-user"), isNull(), eq("message"), isNull(), eq(false), any());
        verifyNoInteractions(old);
    }

    @Test void wrongFileTokenCannotUpload() throws Exception {
        mvc.perform(upload().header("X-KB-File-Token", "other-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(unified, old);
    }

    @Test void forceFlagIsPreserved() throws Exception {
        mvc.perform(upload().param("force", "true").header("X-KB-File-Token", "file-token"))
                .andExpect(status().isOk());
        verify(unified).legacyFile(eq("req"), eq("reported-user"), isNull(), eq("message"), isNull(), eq(true), any());
    }

    private MockMultipartHttpServletRequestBuilder upload() {
        return multipart("/api/v1/ingest/file")
                .file(new MockMultipartFile("file", "note.md", "text/markdown", "# Knowledge".getBytes(StandardCharsets.UTF_8)))
                .param("requestId", "req").param("userId", "reported-user").param("messageId", "message");
    }
}
