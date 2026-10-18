package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.packet.PacketTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据包检查：非法坐标（INVALID_PACKET）、单刻瞬移（FAST_MOVE）、落地未受伤（NO_FALL）。
 *
 * <p>坐标与落地判定读的是归一化字段（{@link PacketTypes}），不接触平台数据包对象。
 *
 * <p>NO_FALL 有两条入口，优先用真实包：
 * <ul>
 *   <li>真实包口径：移动包自带 {@code onGround}，客户端刚声称落地时，与该玩家上一个坐标包的
 *       竖向落差即这一次下坠的距离。落差超过阈值还声称落地，就是「落地未受伤」。</li>
 *   <li>事件驱动口径：平台只能合成 {@link PacketTypes#PLAYER_GROUND} 并直接给出 {@code deltaY}
 *       时，退化用该字段（Geyser / Nukkit 老路径，保留不回归）。</li>
 * </ul>
 */
public final class PacketCheck implements ServerCheck {

    /** 单刻位移上限（格）。超过视为瞬移。公开是为了让拉普拉斯侧锚定注入幅度。 */
    public static final double TELEPORT_THRESHOLD = 10.0;

    /** 落地前竖向落差阈值（格）。超过且声称落地即判 NO_FALL。 */
    public static final double NO_FALL_MIN_DROP = 3.0;

    /** 每玩家上一个坐标包的位置与落地声明。 */
    private final ConcurrentHashMap<String, MoveState> lastStates = new ConcurrentHashMap<>();

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

        boolean hasPosition = hasCoordinates(packet);
        if (hasPosition) {
            double x = packet.num(PacketTypes.FIELD_X, 0);
            double y = packet.num(PacketTypes.FIELD_Y, 0);
            double z = packet.num(PacketTypes.FIELD_Z, 0);
            if (isBad(x) || isBad(y) || isBad(z)) {
                results.add(DetectionResult.violation(CheckType.PACKET, "INVALID_PACKET", 95,
                        "坐标包含 NaN 或 Infinity", packet.timestampMs()));
            } else if (!isSpectator(packet)) {
                Position current = new Position(x, y, z);
                boolean onGround = packet.bool(PacketTypes.FIELD_ON_GROUND, false);
                MoveState previous = lastStates.put(packet.playerId(), new MoveState(current, onGround));
                if (previous != null) {
                    checkFastMove(results, packet, previous, current);
                    checkNoFallFromFlight(results, packet, previous, current, onGround);
                }
            }
        }

        // 事件驱动口径的落地包：平台直接给出 deltaY（下坠为负），没有坐标历史可用。
        if (PacketTypes.PLAYER_GROUND.equals(packet.packetType())
                && packet.has(PacketTypes.FIELD_DELTA_Y)) {
            double deltaY = packet.num(PacketTypes.FIELD_DELTA_Y, 0);
            if (deltaY < -NO_FALL_MIN_DROP) {
                results.add(noFallViolation(-deltaY, packet.timestampMs()));
            }
        }
        return results;
    }

    @Override
    public void reset(String playerId) {
        lastStates.remove(playerId);
    }

    private void checkFastMove(List<DetectionResult> results, PacketView packet,
                              MoveState previous, Position current) {
        double delta = previous.position().distanceTo(current);
        if (delta > TELEPORT_THRESHOLD) {
            results.add(DetectionResult.violation(CheckType.PACKET, "FAST_MOVE", 90,
                    String.format("单刻位移 %.2f 块，超过阈值 %.0f", delta, TELEPORT_THRESHOLD),
                    packet.timestampMs()));
        }
    }

    /**
     * 真实包口径的落地判定：本包声称落地、上一包还在高空、落差够大，就是「落地未受伤」。
     * 上一包本身也声称落地（在地面走动）时不判，避免把正常跳跃与台阶当成作弊。
     */
    private void checkNoFallFromFlight(List<DetectionResult> results, PacketView packet,
                                       MoveState previous, Position current, boolean onGround) {
        if (!onGround || previous.onGround()) {
            return;
        }
        // 下坠时 current.y 小于 previous.y，落差取正值
        double drop = previous.position().y() - current.y();
        if (drop > NO_FALL_MIN_DROP) {
            results.add(noFallViolation(drop, packet.timestampMs()));
        }
    }

    private static DetectionResult noFallViolation(double drop, long timestampMs) {
        return DetectionResult.violation(CheckType.PACKET, "NO_FALL", 70,
                String.format("落地前竖向落差 %.2f 块，超过阈值 %.1f", drop, NO_FALL_MIN_DROP),
                timestampMs);
    }

    private static boolean hasCoordinates(PacketView packet) {
        return packet.has(PacketTypes.FIELD_X)
                && packet.has(PacketTypes.FIELD_Y)
                && packet.has(PacketTypes.FIELD_Z);
    }

    private static boolean isBad(double v) {
        return Double.isNaN(v) || Double.isInfinite(v);
    }

    private static boolean isSpectator(PacketView packet) {
        String mode = packet.str(PacketTypes.FIELD_GAME_MODE);
        return mode != null && "SPECTATOR".equalsIgnoreCase(mode);
    }

    /** 玩家上一个坐标包的位置与落地声明。 */
    private record MoveState(Position position, boolean onGround) {
    }
}
