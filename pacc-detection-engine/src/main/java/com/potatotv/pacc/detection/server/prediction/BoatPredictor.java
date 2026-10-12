package com.potatotv.pacc.detection.server.prediction;

import java.util.HashSet;
import java.util.Set;

/**
 * 载具（船）预测器：船体加速度与水面阻力（文档 §2.2 BoatFly 行，物理要素「载具」）。
 */
public final class BoatPredictor implements MovementPredictor {

    @Override
    public Set<PredictedPosition> predict(PlayerState state, TickInput input) {
        Set<PredictedPosition> candidates = new HashSet<>();

        double vy = PhysicsSimulator.stepVertical(state.vy());
        if (state.inWater()) {
            vy = state.vy() * PhysicsSimulator.BOAT_DRAG - 0.01;
        }

        double yawRad = Math.toRadians(state.yaw());
        double accel = state.inWater() ? PhysicsSimulator.BOAT_ACCEL : PhysicsSimulator.BOAT_ACCEL * 0.5;
        double forward = input.forwardImpulse() * accel;
        double vx = state.vx() * (state.inWater() ? PhysicsSimulator.BOAT_DRAG : 0.5) - Math.sin(yawRad) * forward;
        double vz = state.vz() * (state.inWater() ? PhysicsSimulator.BOAT_DRAG : 0.5) + Math.cos(yawRad) * forward;

        candidates.add(new PredictedPosition(
                state.x() + vx, state.y() + vy, state.z() + vz,
                vx, vy, vz, false, 0.05));
        return candidates;
    }
}
