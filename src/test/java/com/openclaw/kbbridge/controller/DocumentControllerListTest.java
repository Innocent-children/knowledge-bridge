package com.openclaw.kbbridge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openclaw.kbbridge.entity.KnowledgeDocumentEntity;
import com.openclaw.kbbridge.repository.KnowledgeDocumentMapper;
import com.openclaw.kbbridge.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DocumentController 分页列表端点单元测试。
 * Requirements: 9.2, 9.3, 9.4
 */
@WebMvcTest(controllers = DocumentController.class)
@AutoConfigureMockMvc(addFilters = false)
class DocumentControllerListTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private DocumentService documentService;

        @MockitoBean
        private KnowledgeDocumentMapper knowledgeDocumentMapper;

        @Test
        void defaultPagination_returnsPage1Size20() throws Exception {
                Page<KnowledgeDocumentEntity> mockPage = new Page<>(1, 20);
                mockPage.setRecords(List.of(createDocument(1L, "COMPLETED", "GUIDE")));
                mockPage.setTotal(1);

                when(knowledgeDocumentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/documents"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.current").value(1))
                                .andExpect(jsonPath("$.size").value(20))
                                .andExpect(jsonPath("$.total").value(1))
                                .andExpect(jsonPath("$.records").isArray())
                                .andExpect(jsonPath("$.records.length()").value(1));
        }

        @Test
        void customPageAndSize_returnsRequestedPage() throws Exception {
                Page<KnowledgeDocumentEntity> mockPage = new Page<>(2, 5);
                mockPage.setRecords(List.of(
                                createDocument(6L, "COMPLETED", "QA"),
                                createDocument(7L, "COMPLETED", "QA")));
                mockPage.setTotal(12);

                when(knowledgeDocumentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/documents")
                                .param("page", "2")
                                .param("size", "5"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.current").value(2))
                                .andExpect(jsonPath("$.size").value(5))
                                .andExpect(jsonPath("$.total").value(12))
                                .andExpect(jsonPath("$.records.length()").value(2));
        }

        @Test
        void statusFilter_returnsOnlyMatchingRecords() throws Exception {
                Page<KnowledgeDocumentEntity> mockPage = new Page<>(1, 20);
                mockPage.setRecords(List.of(
                                createDocument(1L, "DISABLED", "GUIDE"),
                                createDocument(2L, "DISABLED", "QA")));
                mockPage.setTotal(2);

                when(knowledgeDocumentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/documents")
                                .param("status", "DISABLED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.total").value(2))
                                .andExpect(jsonPath("$.records[0].status").value("DISABLED"))
                                .andExpect(jsonPath("$.records[1].status").value("DISABLED"));
        }

        @Test
        void emptyResultSet_returnsEmptyRecords() throws Exception {
                Page<KnowledgeDocumentEntity> mockPage = new Page<>(1, 20);
                mockPage.setRecords(List.of());
                mockPage.setTotal(0);

                when(knowledgeDocumentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/documents")
                                .param("status", "FAILED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.total").value(0))
                                .andExpect(jsonPath("$.records").isArray())
                                .andExpect(jsonPath("$.records.length()").value(0));
        }

        private KnowledgeDocumentEntity createDocument(Long id, String status, String knowledgeType) {
                KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
                entity.setId(id);
                entity.setTaskId(id + 100);
                entity.setKnowledgeType(knowledgeType);
                entity.setTitle("Document " + id);
                entity.setTopic("Topic " + id);
                entity.setStatus(status);
                entity.setReviewStatus("APPROVED");
                entity.setVersion(1);
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                return entity;
        }
}
