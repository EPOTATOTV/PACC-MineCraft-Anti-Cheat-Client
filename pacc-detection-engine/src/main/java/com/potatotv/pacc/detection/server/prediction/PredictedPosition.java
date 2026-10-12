package com.potatotv.pacc.detection.server.prediction;

/**
 * 预测位置：预测器产出的一个可能落点（技术文档 §7.1）。
 * 预测器会按输入不确定性产出多个候选（如 0.03 舍入容忍、活塞/实体碰撞不确定性）。
 *
 * @param x           预测 x
 * @param y           预测 y
 * @param z           预测 z
 * @param vx          预测后水平速度 x
 * @param vy          预测后垂直速度
 * @param vz          预测后水平速度 z
 * @param onGround    预测后是否着地
 * @param uncertainty 该候选的额外不确定半径（格）
 */
public record PredictedPosition(
        double x, double y, double z,
        double vx, double vy, double vz,
        boolean onGround,
        double uncertainty) {
}
