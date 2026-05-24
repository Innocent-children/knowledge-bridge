package com.openclaw.kbbridge.model.enums;

/**
 * 命中置信度枚举，基于 top1 检索分数分类。
 */
public enum Confidence {
    /**
     * top1 score >= 0.8
     */
    HIGH,
    /**
     * 0.6 <= top1 score < 0.8
     */
    MEDIUM,
    /**
     * top1 score < 0.6
     */
    LOW;

    /**
     * 根据 top1 检索分数返回对应的置信度等级。
     *
     * @param score top1 检索分数
     * @return 对应的 Confidence 枚举值
     */
    public static Confidence fromScore(double score) {
        if (score >= 0.8) {
            return HIGH;
        } else if (score >= 0.6) {
            return MEDIUM;
        } else {
            return LOW;
        }
    }
}
