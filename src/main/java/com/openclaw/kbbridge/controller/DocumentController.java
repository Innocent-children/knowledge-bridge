package com.openclaw.kbbridge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openclaw.kbbridge.dto.document.DocumentDetailResponse;
import com.openclaw.kbbridge.entity.KnowledgeDocumentEntity;
import com.openclaw.kbbridge.repository.KnowledgeDocumentMapper;
import com.openclaw.kbbridge.service.DocumentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 文档管理控制器。
 * <p>
 * 提供文档禁用、启用和详情查询端点：
 * <ul>
 * <li>GET /api/v1/documents — 分页查询知识文档列表</li>
 * <li>POST /api/v1/document/{documentId}/disable — 禁用文档</li>
 * <li>POST /api/v1/document/{documentId}/enable — 启用文档</li>
 * <li>GET /api/v1/document/{documentId} — 查询文档详情</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public DocumentController(DocumentService documentService,
                              KnowledgeDocumentMapper knowledgeDocumentMapper) {
        this.documentService = documentService;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
    }

    /**
     * 知识文档分页列表端点。
     * <p>
     * 支持按 status 和 knowledgeType 可选过滤，返回分页结果。
     * </p>
     *
     * @param page          页码（默认 1）
     * @param size          每页大小（默认 20）
     * @param status        文档状态过滤（可选）
     * @param knowledgeType 知识类型过滤（可选）
     * @return 分页知识文档列表
     */
    @GetMapping("/documents")
    public ResponseEntity<Page<KnowledgeDocumentEntity>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String knowledgeType) {
        log.info("查询知识文档列表: page={}, size={}, status={}, knowledgeType={}", page, size, status, knowledgeType);
        Page<KnowledgeDocumentEntity> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(KnowledgeDocumentEntity::getStatus, status);
        }
        if (knowledgeType != null && !knowledgeType.isBlank()) {
            wrapper.eq(KnowledgeDocumentEntity::getKnowledgeType, knowledgeType);
        }
        wrapper.orderByDesc(KnowledgeDocumentEntity::getUpdatedAt);
        Page<KnowledgeDocumentEntity> result = knowledgeDocumentMapper.selectPage(pageParam, wrapper);
        return ResponseEntity.ok(result);
    }

    /**
     * 禁用文档端点。
     * <p>
     * 将文档状态置为 DISABLED，调用 RAGFlow API 排除文档，
     * 但不物理删除 MinIO 中的文件。
     * </p>
     *
     * @param documentId 文档 ID
     * @return 200 OK
     */
    @PostMapping("/document/{documentId}/disable")
    public ResponseEntity<Map<String, String>> disable(@PathVariable Long documentId) {
        log.info("收到文档禁用请求: documentId={}", documentId);
        documentService.disable(documentId);
        return ResponseEntity.ok(Map.of("message", "文档已禁用"));
    }

    /**
     * 启用文档端点。
     * <p>
     * 恢复文档状态，调用 RAGFlow API 重新将文档加入检索。
     * </p>
     *
     * @param documentId 文档 ID
     * @return 200 OK
     */
    @PostMapping("/document/{documentId}/enable")
    public ResponseEntity<Map<String, String>> enable(@PathVariable Long documentId) {
        log.info("收到文档启用请求: documentId={}", documentId);
        documentService.enable(documentId);
        return ResponseEntity.ok(Map.of("message", "文档已启用"));
    }

    /**
     * 查询文档详情端点。
     *
     * @param documentId 文档 ID
     * @return 文档详情，文档不存在返回 404
     */
    @GetMapping("/document/{documentId}")
    public ResponseEntity<DocumentDetailResponse> getDetail(@PathVariable Long documentId) {
        log.info("查询文档详情: documentId={}", documentId);
        DocumentDetailResponse detail = documentService.getDetail(documentId);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }
}
