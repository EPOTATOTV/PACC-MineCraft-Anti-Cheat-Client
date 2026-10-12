package com.potatotv.pacc.detection.server.prediction;

/**
 * 预测与实际的位置偏差（技术文档 §7.1 OffsetResult）。
 *
 * @param dx          x 偏差
 * @param dy          y 偏差
 * @param dz          z 偏差
 * @param horizontal  水平偏差绝对值
 * @param vertical    垂直偏差绝对值
 * @param total       三轴总偏差
 * @param withinTolerance 是否落在预测集合的容忍范围内
 */
public record OffsetResult(
        double dx, double dy, double dz,
        double horizontal, double vertical, double total,
        boolean withinTolerance) {

    public static OffsetResult none() {
        return new OffsetResult(0, 0, 0, 0, 0, 0, true);
    }
}
