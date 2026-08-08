package com.openclaw.kbbridge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库任务实体，对应 kb_ingest_task 表。
 */
@Data
@TableName("kb_ingest_task")
public class IngestTaskEntity {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 请求唯一标识
     */
    private String requestId;

    /**
     * 来源渠道 (feishu 等)
     */
    private String sourceChannel;

    /**
     * 来源类型 (FEISHU_CHAT/MARKDOWN/ATTACHMENT)
     */
    private String sourceType;

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 群聊 ID
     */
    private String chatId;

    /**
     * 关联的消息 ID 列表 (JSON)
     */
    private String messageIdsJson;

    /**
     * 入库状态 (DocumentStatus 枚举值)
     */
    private String status;

    /**
     * 审核状态 (ReviewStatus 枚举值)
     */
    private String reviewStatus;

    /**
     * MinIO 原始件路径
     */
    private String rawObjectKey;

    private String fileName;

    private String mimeType;

    private Long fileSize;

    private String sourceMessageId;

    private String externalDocumentId;

    private String externalJobId;

    private String errorCode;

    private Boolean retryable;

    /**
     * MinIO Guide 处理件路径
     */
    private String processedGuideKey;

    /**
     * MinIO Q&A 处理件路径
     */
    private String processedQaKey;

    /**
     * 原始内容 SHA-256 哈希
     */
    private String contentHash;

    /**
     * 处理器版本
     */
    private String processorVersion;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 重试次数（补偿任务用）
     */
    private Integer retryCount;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
