package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 移动检查：水平超速（SPEED）与空中上升（FLY）。
 * 创造性 / 旁观模式跳过。
 */
public final class MoveCheck implements ServerCheck {

    /** 速度判定留一点余量，避免把正常的冲刺跳跃当成作弊。 */
    private static final double SPEED_TOLERANCE = 1.5;

    private final ServerPluginConfig config;
    private final ConcurrentHashMap<String, Integer> airborneTicks = new ConcurrentHashMap<>();

    public MoveCheck(ServerPluginConfig config) {
        this.config = config;
    }

    @Override
    public CheckType type() {
        return CheckType.MOVE;
    }

    @Override
    public List<DetectionResult> onMove(PlayerPosition from, PlayerPosition to) {
        if (from == null || to == null) {
            return List.of();
        }
        GameMode mode = to.gameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return List.of();
        }

        String playerId = to.playerId();
        double ticks = (to.timestampMs() - from.timestampMs()) / 50.0;
        if (ticks <= 0) {
            ticks = 1.0;
        }

        List<DetectionResult> results = new ArrayList<>(2);
        Position a = from.pos();
        Position b = to.pos();

        double hSpeed = a.horizontalDistanceTo(b) / ticks;
        double speedLimit = config.maxHorizontalSpeedPerTick() * SPEED_TOLERANCE;
        if (hSpeed > speedLimit) {
            results.add(DetectionResult.violation(CheckType.MOVE, "SPEED", scale(hSpeed, speedLimit),
                    String.format("水平速度 %.2f 块/刻 超过阈值 %.2f", hSpeed, speedLimit), to.timestampMs()));
        }

        boolean airborne = !to.onGround();
        double climb = (b.y() - a.y()) / ticks;
        if (airborne && climb > config.maxVerticalClimbPerTick()) {
            results.add(DetectionResult.violation(CheckType.MOVE, "FLY", 80,
                    String.format("空中上升 %.2f 块/刻 超过阈值 %.2f", climb, config.maxVerticalClimbPerTick()),
                    to.timestampMs()));
        }

        int ticksAir = airborne
                ? airborneTicks.merge(playerId, (int) Math.max(1, Math.round(ticks)), Integer::sum)
                : resetAirborne(playerId);
        if (airborne && ticksAir >= config.flyAirborneTicksTrigger() && b.y() > a.y()) {
            results.add(DetectionResult.violation(CheckType.MOVE, "FLY", 75,
                    "连续滞空 " + ticksAir + " 刻且持续上升", to.timestampMs()));
        }
        return results;
    }

    @Override
    public void reset(String playerId) {
        airborneTicks.remove(playerId);
    }

    private int resetAirborne(String playerId) {
        airborneTicks.remove(playerId);
        return 0;
    }

    private static int scale(double value, double limit) {
        double excess = (value - limit) / limit;
        return (int) Math.min(100, 70 + excess * 50);
    }
}
