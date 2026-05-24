package com.openclaw.kbbridge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 查询日志实体，对应 kb_query_log 表。
 */
@Data
@TableName("kb_query_log")
public class QueryLogEntity {

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
     * 用户 ID
     */
    private String userId;

    /**
     * 群聊 ID
     */
    private String chatId;

    /**
     * 会话标识
     */
    private String sessionKey;

    /**
     * 用户问题
     */
    private String question;

    /**
     * 路由结果（QueryRoute 枚举名）
     */
    private String route;

    /**
     * 命中置信度（Confidence 枚举名）
     */
    private String retrievalQuality;

    /**
     * 返回证据条数
     */
    private Integer sourceCount;

    /**
     * 最高命中分数
     */
    private BigDecimal topScore;

    /**
     * 查询状态（QueryStatus 枚举名）
     */
    private String status;

    /**
     * 完整响应 JSON（用于幂等缓存）
     */
    private String responseJson;

    /**
     * RAGFlow 调用耗时(ms)
     */
    private Integer ragflowLatencyMs;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
