package com.openclaw.kbbridge.dto.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 审核拒绝请求 DTO。
 *
 * @param taskId   入库任务 ID（必填）
 * @param reviewer 审核人（必填）
 * @param comment  审核意见（可选）
 */
public record ReviewRejectRequest(
                // 待拒绝的入库任务 ID，必填
                @NotNull(message = "taskId 不能为空") Long taskId,
                // 审核人标识，必填
                @NotBlank(message = "reviewer 不能为空") String reviewer,
                // 拒绝理由/备注，可选
                String comment) {
}
