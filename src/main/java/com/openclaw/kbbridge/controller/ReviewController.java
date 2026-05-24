package com.openclaw.kbbridge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openclaw.kbbridge.dto.review.*;
import com.openclaw.kbbridge.service.ReviewService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 审核控制器。
 * <p>
 * 提供审核通过、审核拒绝、待审核列表查询、审核详情查询、批量审核等端点：
 * <ul>
 * <li>POST /api/v1/review/approve — 审核通过</li>
 * <li>POST /api/v1/review/reject — 审核拒绝</li>
 * <li>GET /api/v1/review/pending — 分页查询待审核列表</li>
 * <li>GET /api/v1/review/{taskId} — 查询审核详情</li>
 * <li>POST /api/v1/review/batch — 批量审核</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 审核通过端点。
     *
     * @param request 审核通过请求
     * @return 200 OK
     */
    @PostMapping("/review/approve")
    public ResponseEntity<Map<String, String>> approve(@Valid @RequestBody ReviewApproveRequest request) {
        log.info("收到审核通过请求: taskId={}, reviewer={}", request.taskId(), request.reviewer());
        reviewService.approve(request);
        return ResponseEntity.ok(Map.of("message", "审核通过"));
    }

    /**
     * 审核拒绝端点。
     *
     * @param request 审核拒绝请求
     * @return 200 OK
     */
    @PostMapping("/review/reject")
    public ResponseEntity<Map<String, String>> reject(@Valid @RequestBody ReviewRejectRequest request) {
        log.info("收到审核拒绝请求: taskId={}, reviewer={}", request.taskId(), request.reviewer());
        reviewService.reject(request);
        return ResponseEntity.ok(Map.of("message", "审核拒绝"));
    }

    /**
     * 分页查询待审核列表端点。
     *
     * @param page       页码（默认 1）
     * @param size       每页大小（默认 20）
     * @param sourceType 来源类型过滤（可选）
     * @return 分页待审核列表
     */
    @GetMapping("/review/pending")
    public ResponseEntity<Page<ReviewListResponse>> getPendingList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sourceType) {
        log.info("查询待审核列表: page={}, size={}, sourceType={}", page, size, sourceType);
        Page<ReviewListResponse> result = reviewService.getPendingList(page, size, sourceType);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询审核详情端点。
     *
     * @param taskId 入库任务 ID
     * @return 审核详情，任务不存在返回 404
     */
    @GetMapping("/review/{taskId}")
    public ResponseEntity<ReviewDetailResponse> getDetail(@PathVariable Long taskId) {
        log.info("查询审核详情: taskId={}", taskId);
        ReviewDetailResponse detail = reviewService.getDetail(taskId);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /**
     * 批量审核端点。
     *
     * @param request 批量审核请求
     * @return 批量审核结果
     */
    @PostMapping("/review/batch")
    public ResponseEntity<ReviewBatchResponse> batchReview(@Valid @RequestBody ReviewBatchRequest request) {
        log.info("收到批量审核请求: reviewer={}, itemCount={}", request.reviewer(), request.items().size());
        ReviewBatchResponse response = reviewService.batchReview(request);
        return ResponseEntity.ok(response);
    }
}
