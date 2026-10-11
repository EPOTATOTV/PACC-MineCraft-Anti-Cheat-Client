package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientCheckType;
import com.potatotv.pacc.client.core.model.ClientDetectionResult;
import com.potatotv.pacc.client.core.model.GameMode;
import com.potatotv.pacc.client.core.model.PlayerMovement;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端移动检测：水平超速（SPEED）与空中上升 / 长时间滞空（FLY）。
 *
 * <p>玩家端只观测本地玩家，状态是单份的（不按玩家区分）。创造 / 旁观模式跳过。
 */
public final class MoveDetector {

    /** 速度判定留一点余量，避免把正常的冲刺跳跃当成作弊。 */
    private static final double SPEED_TOLERANCE = 1.5;

    private final double maxHorizontalSpeedPerTick;
    private final double maxVerticalClimbPerTick;
    private final int flyAirborneTicksTrigger;

    private int airborneTicks;

    public MoveDetector() {
        this(0.42, 0.42, 20);
    }

    public MoveDetector(double maxHorizontalSpeedPerTick, double maxVerticalClimbPerTick, int flyAirborneTicksTrigger) {
        this.maxHorizontalSpeedPerTick = maxHorizontalSpeedPerTick;
        this.maxVerticalClimbPerTick = maxVerticalClimbPerTick;
        this.flyAirborneTicksTrigger = flyAirborneTicksTrigger;
    }

    public List<ClientDetectionResult> onMove(PlayerMovement from, PlayerMovement to) {
        if (from == null || to == null) {
            return List.of();
        }
        GameMode mode = to.gameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return List.of();
        }

        double ticks = (to.timestampMs() - from.timestampMs()) / 50.0;
        if (ticks <= 0) {
            ticks = 1.0;
        }

        List<ClientDetectionResult> results = new ArrayList<>(2);

        double hSpeed = from.horizontalDistanceTo(to) / ticks;
        double speedLimit = maxHorizontalSpeedPerTick * SPEED_TOLERANCE;
        if (hSpeed > speedLimit) {
            results.add(ClientDetectionResult.violation(ClientCheckType.MOVE, "SPEED", scale(hSpeed, speedLimit),
                    String.format("水平速度 %.2f 块/刻 超过阈值 %.2f", hSpeed, speedLimit), to.timestampMs()));
        }

        boolean airborne = !to.onGround();
        double climb = (to.y() - from.y()) / ticks;
        if (airborne && climb > maxVerticalClimbPerTick) {
            results.add(ClientDetectionResult.violation(ClientCheckType.MOVE, "FLY", 80,
                    String.format("空中上升 %.2f 块/刻 超过阈值 %.2f", climb, maxVerticalClimbPerTick),
                    to.timestampMs()));
        }

        int ticksAir;
        if (airborne) {
            airborneTicks += (int) Math.max(1, Math.round(ticks));
            ticksAir = airborneTicks;
        } else {
            airborneTicks = 0;
            ticksAir = 0;
        }
        if (airborne && ticksAir >= flyAirborneTicksTrigger && to.y() > from.y()) {
            results.add(ClientDetectionResult.violation(ClientCheckType.MOVE, "FLY", 75,
                    "连续滞空 " + ticksAir + " 刻且持续上升", to.timestampMs()));
        }
        return results;
    }

    /** 本地玩家换图 / 重生时重置历史状态。 */
    public void reset() {
        airborneTicks = 0;
    }

    private static int scale(double value, double limit) {
        double excess = (value - limit) / limit;
        return (int) Math.min(100, 70 + excess * 50);
    }
}
