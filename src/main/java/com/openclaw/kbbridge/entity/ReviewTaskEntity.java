package com.openclaw.kbbridge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核任务实体，对应 kb_review_task 表。
 */
@Data
@TableName("kb_review_task")
public class ReviewTaskEntity {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联入库任务 ID
     */
    private Long taskId;

    /**
     * 审核状态
     */
    private String reviewStatus;

    /**
     * 审核人
     */
    private String reviewer;

    /**
     * 审核意见
     */
    private String comment;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
