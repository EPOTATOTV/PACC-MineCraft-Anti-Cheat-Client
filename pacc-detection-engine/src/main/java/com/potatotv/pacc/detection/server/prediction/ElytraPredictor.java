package com.potatotv.pacc.detection.server.prediction;

import java.util.HashSet;
import java.util.Set;

/**
 * 鞘翅滑翔预测器：俯仰驱动的升力/阻力模型（文档 §2.2 Glide 行，物理要素「附魔」）。
 */
public final class ElytraPredictor implements MovementPredictor {

    @Override
    public Set<PredictedPosition> predict(PlayerState state, TickInput input) {
        Set<PredictedPosition> candidates = new HashSet<>();
        double pitchRad = Math.toRadians(state.pitch());

        double vy = state.vy() - PhysicsSimulator.ELYTRA_GRAVITY - Math.cos(pitchRad) * Math.abs(state.vy()) * 0.06;
        double horizontalSpeed = Math.hypot(state.vx(), state.vz());
        double lift = Math.sin(pitchRad) * horizontalSpeed * 0.06;
        vy += lift;
        vy = Math.max(-PhysicsSimulator.ELYTRA_MAX_SPEED, Math.min(PhysicsSimulator.ELYTRA_MAX_SPEED, vy));

        // 滑翔方向沿视线方向
        double yawRad = Math.toRadians(state.yaw());
        double speed = Math.min(PhysicsSimulator.ELYTRA_MAX_SPEED, horizontalSpeed * 0.99 + 0.01);
        double vx = -Math.sin(yawRad) * Math.cos(pitchRad) * speed;
        double vz = Math.cos(yawRad) * Math.cos(pitchRad) * speed;

        candidates.add(new PredictedPosition(
                state.x() + vx, state.y() + vy, state.z() + vz,
                vx, vy, vz, false, 0.05));
        return candidates;
    }
}
