package com.openclaw.kbbridge.model.enums;

/**
 * 文档/入库任务状态枚举，驱动入库状态机。
 */
public enum DocumentStatus {
    /**
     * 已接收入库请求
     */
    RECEIVED,
    /**
     * 原始件已保存到 MinIO
     */
    RAW_STORED,
    /**
     * LLM 知识化重写中
     */
    PROCESSING,
    /**
     * 处理件已保存到 MinIO，入库完成
     */
    COMPLETED,
    /**
     * 处理失败
     */
    FAILED,
    /**
     * 已禁用，从检索中排除
     */
    DISABLED
}
