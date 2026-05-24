package com.openclaw.kbbridge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档实体，对应 kb_document 表。
 */
@Data
@TableName("kb_document")
public class KnowledgeDocumentEntity {

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
     * 知识类型 (GUIDE/QA)
     */
    private String knowledgeType;

    /**
     * 文档标题
     */
    private String title;

    /**
     * 主题
     */
    private String topic;

    /**
     * 标签列表 (JSON)
     */
    private String tagsJson;

    /**
     * 审核状态
     */
    private String reviewStatus;

    /**
     * 文档状态
     */
    private String status;

    /**
     * RAGFlow Dataset 名称
     */
    private String datasetName;

    /**
     * RAGFlow 文档 ID
     */
    private String ragflowDocumentId;

    /**
     * 完整元数据 (JSON)
     */
    private String metadataJson;

    /**
     * 文档版本号
     */
    private Integer version;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
