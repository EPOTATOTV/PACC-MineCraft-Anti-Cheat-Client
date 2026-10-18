package com.potatotv.pacc.detection.server.prediction;

import java.util.HashSet;
import java.util.Set;

/**
 * 普通（地面 / 空中）预测器：基础实现，含重力、跳跃、疾跑、水平摩擦与 0.03 舍入容忍。
 * 碰撞箱与世界副本的精细约束由 {@code WorldReplica} 增强后叠加。
 */
public final class NormalPredictor implements MovementPredictor {

    @Override
    public Set<PredictedPosition> predict(PlayerState state, TickInput input) {
        Set<PredictedPosition> candidates = new HashSet<>();
        boolean sprint = input.sprint() || state.sprinting();
        double moveSpeed = PhysicsSimulator.moveSpeed(sprint, state.effectLevel(PlayerState.EFFECT_SPEED),
                state.effectLevel(PlayerState.EFFECT_SLOWNESS));

        double vy = PhysicsSimulator.stepVertical(state.vy());
        boolean jumped = false;
        if (input.jump() && state.onGround()) {
            vy = PhysicsSimulator.jumpVelocity(state.effectLevel(PlayerState.EFFECT_JUMP_BOOST));
            jumped = true;
        }
        if (state.effectLevel(PlayerState.EFFECT_SLOW_FALLING) > 0 && vy < 0) {
            vy = PhysicsSimulator.stepVerticalSlowFalling(state.vy());
        }
        double bounce = state.blockBounciness();
        if (bounce <= 0.0 && state.onSlimeBlock()) {
            bounce = 0.8; // 旧状态位没有脚下方块材质时的兼容口径
        }
        if (bounce > 0.0 && !input.sneak() && state.fallDistance() > 0.5 && !jumped && state.vy() < 0) {
            vy = -state.vy() * bounce;
        }
        if (state.inBubbleColumn()) {
            vy = state.vy() > 0 ? 0.9 : -0.9; // 气泡柱上/下行
        }
        if (state.effectLevel(PlayerState.EFFECT_LEVITATION) > 0) {
            vy = 0.01 * state.effectLevel(PlayerState.EFFECT_LEVITATION);
        }

        // 水平：先摩擦后加速度（Java 版 1.8+ 口径）。滑度来自脚下方块（P-01）：
        // 冰面 0.98、黏液/蜂蜜 0.8、默认 0.6；版本差异只体现在属性表，不在公式里分支
        double slip = state.onGround() ? state.blockSlipperiness() : 1.0;
        double friction = state.onGround() ? PhysicsSimulator.groundFriction(slip) : PhysicsSimulator.AIR_DRAG;
        double vx = state.vx() * friction;
        double vz = state.vz() * friction;

        double inputLen = Math.hypot(input.forwardImpulse(), input.strafeImpulse());
        if (inputLen > 0.01) {
            double accel = state.onGround()
                    ? PhysicsSimulator.groundAccel(moveSpeed, slip)
                    : PhysicsSimulator.AIR_ACCEL_BASE * moveSpeed;
            double scale = Math.min(1.0, inputLen);
            double fx = (input.strafeImpulse() / scale) * accel;
            double fz = (input.forwardImpulse() / scale) * accel;
            double[] world = PhysicsSimulator.rotateToYaw(fx, fz, state.yaw());
            vx += world[0];
            vz += world[1];
            if (sprint && jumped) {
                // 疾跑跳跃沿朝向加成
                double rad = Math.toRadians(state.yaw());
                vx += -Math.sin(rad) * 0.2;
                vz += Math.cos(rad) * 0.2;
            }
        }

        // 0.03 舍入容忍：每个轴各出 ±0.03 候选（文档 §5.2 SpeedHack 行）。
        // 贴地候选：onGround 时地面碰撞会抵消重力（世界数据缺失时预测器不知道脚下有方块，
        // 必须枚举「原地站立」这一物理可能分支，否则站立玩家被误判为悬空坠落）。
        if (state.onGround()) {
            candidates.add(new PredictedPosition(
                    state.x() + vx, state.y(), state.z() + vz,
                    vx, 0.0, vz, true, 0.0));
        }
        for (double dy : new double[] {0.0, PhysicsSimulator.ROUNDING_TOLERANCE, -PhysicsSimulator.ROUNDING_TOLERANCE}) {
            candidates.add(new PredictedPosition(
                    state.x() + vx, state.y() + vy + dy, state.z() + vz,
                    vx, vy, vz, vy == 0 && state.onGround(), 0.0));
        }
        return candidates;
    }
}
