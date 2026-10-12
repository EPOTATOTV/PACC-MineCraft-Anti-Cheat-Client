package com.potatotv.pacc.detection.server.prediction;

import java.util.HashSet;
import java.util.Set;

/**
 * 流体（水 / 岩浆）预测器：流体阻力、重力、海豚恩惠与水下疾跑口径（文档 §2.2 Jesus/Dolphin 行）。
 */
public final class WaterPredictor implements MovementPredictor {

    @Override
    public Set<PredictedPosition> predict(PlayerState state, TickInput input) {
        Set<PredictedPosition> candidates = new HashSet<>();
        boolean sprint = input.sprint() || state.sprinting();
        double moveSpeed = PhysicsSimulator.moveSpeed(sprint, state.effectLevel(PlayerState.EFFECT_SPEED),
                state.effectLevel(PlayerState.EFFECT_SLOWNESS));

        double drag = state.inLava() ? 0.5 : PhysicsSimulator.WATER_DRAG;
        double vy = state.vy() * drag;
        vy -= state.inLava() ? 0.02 : PhysicsSimulator.WATER_GRAVITY;
        if (input.jump()) {
            vy = state.inLava() ? 0.24 : 0.04; // 上浮
        }
        if (state.effectLevel(PlayerState.EFFECT_DOLPHINS_GRACE) > 0) {
            vy = Math.max(vy, -0.02);
        }

        double vx = state.vx() * drag;
        double vz = state.vz() * drag;
        double inputLen = Math.hypot(input.forwardImpulse(), input.strafeImpulse());
        if (inputLen > 0.01) {
            double accel = 0.02 * moveSpeed;
            double scale = Math.min(1.0, inputLen);
            double fx = (input.strafeImpulse() / scale) * accel;
            double fz = (input.forwardImpulse() / scale) * accel;
            double[] world = PhysicsSimulator.rotateToYaw(fx, fz, state.yaw());
            vx += world[0];
            vz += world[1];
        }

        for (double dy : new double[] {0.0, PhysicsSimulator.ROUNDING_TOLERANCE, -PhysicsSimulator.ROUNDING_TOLERANCE}) {
            candidates.add(new PredictedPosition(
                    state.x() + vx, state.y() + vy + dy, state.z() + vz,
                    vx, vy, vz, false, 0.0));
        }
        return candidates;
    }
}
