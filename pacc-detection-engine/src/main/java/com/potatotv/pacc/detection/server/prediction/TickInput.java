package com.potatotv.pacc.detection.server.prediction;

/**
 * 客户端单 tick 输入（技术文档 §7.1 TickInput）。
 *
 * @param forwardImpulse 前向输入量（-1..1）
 * @param strafeImpulse  侧向输入量（-1..1）
 * @param jump           本 tick 是否跳跃
 * @param sneak          是否潜行
 * @param sprint         是否疾跑
 * @param timestampMs    输入对应时间
 */
public record TickInput(
        float forwardImpulse,
        float strafeImpulse,
        boolean jump,
        boolean sneak,
        boolean sprint,
        long timestampMs) {

    public static TickInput idle(long timestampMs) {
        return new TickInput(0f, 0f, false, false, false, timestampMs);
    }
}
