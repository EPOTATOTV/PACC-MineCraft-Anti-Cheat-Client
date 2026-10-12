package com.potatotv.pacc.detection.server.prediction;

import java.util.Set;

/**
 * 移动预测器（技术文档 §7.1）。
 *
 * <p>设计哲学（GrimAC 口径）：服务端 1:1 模拟玩家可能的下一 tick 位置——
 * 重力、碰撞、流体、载具、版本差异全部纳入模拟，「物理上不可能」的位移即违规，
 * 用物理不可绕过替代阈值猜测。实现必须线程安全：预测在检测线程异步执行。
 */
public interface MovementPredictor {

    /**
     * 给定玩家状态与输入，预测下一 tick 的可能位置集合。
     */
    Set<PredictedPosition> predict(PlayerState state, TickInput input);

    /**
     * 比较预测集合与实际位置，返回偏差与是否落在容忍范围。
     * 容忍 = 任一候选位置的 0.03 舍入容忍 + 候选自身 uncertainty。
     */
    default OffsetResult compare(Set<PredictedPosition> predicted, Position actual) {
        if (predicted == null || predicted.isEmpty() || actual == null) {
            return OffsetResult.none();
        }
        OffsetResult best = null;
        for (PredictedPosition candidate : predicted) {
            double dx = actual.x() - candidate.x();
            double dy = actual.y() - candidate.y();
            double dz = actual.z() - candidate.z();
            double horizontal = Math.hypot(dx, dz);
            double vertical = Math.abs(dy);
            double total = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double tolerance = PhysicsSimulator.ROUNDING_TOLERANCE + candidate.uncertainty();
            boolean within = horizontal <= tolerance && vertical <= tolerance;
            if (best == null || total < best.total()) {
                best = new OffsetResult(dx, dy, dz, horizontal, vertical, total, within);
            }
        }
        return best;
    }

    /**
     * 按玩家状态选择预测引擎（文档 §7.1 selectEngine 口径）：
     * 鞘翅滑翔 → 水中 → 载具 → 普通地面/空中。
     */
    static MovementPredictor selectEngine(PlayerState s) {
        if (s.isGliding()) {
            return new ElytraPredictor();
        }
        if (s.inWater() || s.inLava()) {
            return new WaterPredictor();
        }
        if (s.ridingBoat()) {
            return new BoatPredictor();
        }
        return new NormalPredictor();
    }
}
