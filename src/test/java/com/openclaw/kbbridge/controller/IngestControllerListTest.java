package com.openclaw.kbbridge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openclaw.kbbridge.entity.IngestTaskEntity;
import com.openclaw.kbbridge.repository.IngestTaskMapper;
import com.openclaw.kbbridge.service.CandidateEvalService;
import com.openclaw.kbbridge.service.IngestService;
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
 * IngestController 分页列表端点单元测试。
 * Requirements: 9.1, 9.3, 9.4
 */
@WebMvcTest(controllers = IngestController.class)
@AutoConfigureMockMvc(addFilters = false)
class IngestControllerListTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private IngestService ingestService;

        @MockitoBean
        private CandidateEvalService candidateEvalService;

        @MockitoBean
        private IngestTaskMapper ingestTaskMapper;

        @Test
        void defaultPagination_returnsPage1Size20() throws Exception {
                Page<IngestTaskEntity> mockPage = new Page<>(1, 20);
                mockPage.setRecords(List.of(createTask(1L, "RECEIVED")));
                mockPage.setTotal(1);

                when(ingestTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/ingest/tasks"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.current").value(1))
                                .andExpect(jsonPath("$.size").value(20))
                                .andExpect(jsonPath("$.total").value(1))
                                .andExpect(jsonPath("$.records").isArray())
                                .andExpect(jsonPath("$.records.length()").value(1));
        }

        @Test
        void customPageAndSize_returnsRequestedPage() throws Exception {
                Page<IngestTaskEntity> mockPage = new Page<>(3, 10);
                mockPage.setRecords(List.of(createTask(21L, "PROCESSING"), createTask(22L, "PROCESSING")));
                mockPage.setTotal(25);

                when(ingestTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/ingest/tasks")
                                .param("page", "3")
                                .param("size", "10"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.current").value(3))
                                .andExpect(jsonPath("$.size").value(10))
                                .andExpect(jsonPath("$.total").value(25))
                                .andExpect(jsonPath("$.records.length()").value(2));
        }

        @Test
        void statusFilter_returnsOnlyMatchingRecords() throws Exception {
                Page<IngestTaskEntity> mockPage = new Page<>(1, 20);
                mockPage.setRecords(List.of(
                                createTask(1L, "FAILED"),
                                createTask(2L, "FAILED")));
                mockPage.setTotal(2);

                when(ingestTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/ingest/tasks")
                                .param("status", "FAILED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.total").value(2))
                                .andExpect(jsonPath("$.records[0].status").value("FAILED"))
                                .andExpect(jsonPath("$.records[1].status").value("FAILED"));
        }

        @Test
        void emptyResultSet_returnsEmptyRecords() throws Exception {
                Page<IngestTaskEntity> mockPage = new Page<>(1, 20);
                mockPage.setRecords(List.of());
                mockPage.setTotal(0);

                when(ingestTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                                .thenReturn(mockPage);

                mockMvc.perform(get("/api/v1/ingest/tasks")
                                .param("status", "COMPLETED"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.total").value(0))
                                .andExpect(jsonPath("$.records").isArray())
                                .andExpect(jsonPath("$.records.length()").value(0));
        }

        private IngestTaskEntity createTask(Long id, String status) {
                IngestTaskEntity entity = new IngestTaskEntity();
                entity.setId(id);
                entity.setRequestId("req-" + id);
                entity.setUserId("user-test");
                entity.setSourceType("MARKDOWN");
                entity.setStatus(status);
                entity.setReviewStatus("CANDIDATE");
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                return entity;
        }
}
