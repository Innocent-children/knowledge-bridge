package com.openclaw.kbbridge.dto.review;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量审核请求 DTO。
 *
 * @param reviewer 审核人（必填）
 * @param items    批量审核项列表（必填，不能为空）
 */
public record ReviewBatchRequest(
                // 审核人标识，必填，批量项共用
                @NotBlank(message = "reviewer 不能为空") String reviewer,
                // 批量审核项列表，必填且不能为空
                @NotEmpty(message = "items 不能为空") @Valid List<BatchItem> items) {

        /**
         * 批量审核项。
         *
         * @param taskId  入库任务 ID（必填）
         * @param action  审核动作（必填，"APPROVE" 或 "REJECT"）
         * @param comment 审核意见（可选）
         */
        public record BatchItem(
                        // 待审核的入库任务 ID，必填
                        @NotNull(message = "taskId 不能为空") Long taskId,
                        // 审核动作，必填，取值 APPROVE 或 REJECT
                        @NotBlank(message = "action 不能为空") String action,
                        // 审核意见，可选
                        String comment) {
        }
}
