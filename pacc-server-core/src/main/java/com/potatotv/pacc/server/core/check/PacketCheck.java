package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据包检查：非法坐标（INVALID_PACKET）、单刻瞬移（FAST_MOVE）、落地未受伤（NO_FALL）。
 * 只读 {@link PacketView#fields()}，不接触平台数据包对象。
 */
public final class PacketCheck implements ServerCheck {

    private static final double TELEPORT_THRESHOLD = 10.0;
    private static final double NO_FALL_MIN_DROP = 3.0;

    private final ConcurrentHashMap<String, Position> lastPositions = new ConcurrentHashMap<>();

    @Override
    public CheckType type() {
        return CheckType.PACKET;
    }

    @Override
    public List<DetectionResult> onPacket(PacketView packet) {
        if (packet == null) {
            return List.of();
        }
        List<DetectionResult> results = new ArrayList<>(2);

        boolean hasX = packet.has("x");
        boolean hasY = packet.has("y");
        boolean hasZ = packet.has("z");
        if (hasX || hasY || hasZ) {
            double x = packet.num("x", 0);
            double y = packet.num("y", 0);
            double z = packet.num("z", 0);
            if (isBad(x) || isBad(y) || isBad(z)) {
                results.add(DetectionResult.violation(CheckType.PACKET, "INVALID_PACKET", 95,
                        "坐标包含 NaN 或 Infinity", packet.timestampMs()));
            } else if (hasX && hasY && hasZ) {
                Position current = new Position(x, y, z);
                Position previous = lastPositions.put(packet.playerId(), current);
                if (previous != null && !isSpectator(packet)) {
                    double delta = previous.distanceTo(current);
                    if (delta > TELEPORT_THRESHOLD) {
                        results.add(DetectionResult.violation(CheckType.PACKET, "FAST_MOVE", 90,
                                String.format("单刻位移 %.2f 块，超过阈值 %.0f", delta, TELEPORT_THRESHOLD),
                                packet.timestampMs()));
                    }
                }
            }
        }

        if ("PLAYER_GROUND".equals(packet.packetType()) && packet.has("deltaY")) {
            double drop = packet.num("deltaY", 0);
            if (drop < -NO_FALL_MIN_DROP) {
                results.add(DetectionResult.violation(CheckType.PACKET, "NO_FALL", 70,
                        String.format("落地前竖向落差 %.2f 块，超过阈值 %.1f", drop, NO_FALL_MIN_DROP),
                        packet.timestampMs()));
            }
        }
        return results;
    }

    @Override
    public void reset(String playerId) {
        lastPositions.remove(playerId);
    }

    private static boolean isBad(double v) {
        return Double.isNaN(v) || Double.isInfinite(v);
    }

    private static boolean isSpectator(PacketView packet) {
        String mode = packet.str("gameMode");
        return mode != null && "SPECTATOR".equalsIgnoreCase(mode);
    }
}
