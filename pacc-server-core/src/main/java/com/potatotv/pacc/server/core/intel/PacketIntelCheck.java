package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据包 intel 检查（技术文档 §5.2 packet 行 / §九 PingSpoof·Disabler 反制）。
 *
 * <p>平台适配层只给字段视图（{@link PacketView}），本检查按包类型分流：
 * 移动包喂包时序（Timer/Blink），事务包回填延迟补偿（TransactionSync），
 * 品牌包做客户端一致性校验，带序号的包做乱序检测。
 */
public final class PacketIntelCheck implements ServerCheck {

    /** 每 N 个移动包发一次事务探测（延迟补偿，§7.2 transaction 机制）。 */
    static final int TRANSACTION_EVERY = 20;

    /** 服务器标称 tick 时长（20 TPS）。 */
    static final double SERVER_TICK_MS = 50.0;

    private final IntelRuntime runtime;
    private final AtomicInteger movementPackets = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> perPlayerPackets = new ConcurrentHashMap<>();

    public PacketIntelCheck(IntelRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public CheckType type() {
        return CheckType.PACKET;
    }

    @Override
    public List<DetectionResult> onPacket(PacketView packet) {
        List<DetectionResult> results = new ArrayList<>();
        String type = packet.packetType() == null ? "" : packet.packetType().toLowerCase(Locale.ROOT);
        String playerId = packet.playerId();

        if (isMovementPacket(type)) {
            runtime.packetTiming().recordMovement(playerId, packet.timestampMs());
            // Timer / Blink（§5.2：tick 速率 > 1.005 倍，服务器 TPS 归一化由 SERVER_TICK_MS 承担）
            checkAndCollect(results,
                    runtime.packetTiming().check(playerId, (long) SERVER_TICK_MS));
            if (perPlayerPackets.computeIfAbsent(playerId, unused -> new AtomicInteger())
                    .incrementAndGet() % TRANSACTION_EVERY == 0) {
                runtime.latencyCompensator().sendTransaction();
            }
        } else if (type.contains("transaction") || type.contains("pong")) {
            Object idField = packet.str("id") != null ? packet.str("id") : packet.str("transactionId");
            if (idField != null) {
                try {
                    runtime.latencyCompensator().ack(Integer.parseInt(idField.toString()), packet.timestampMs());
                } catch (NumberFormatException ignored) {
                    // 字段不是数字，忽略
                }
            }
        } else if (type.contains("brand")) {
            checkAndCollect(results,
                    runtime.brandValidator().check(playerId, packet.str("brand"), List.of()));
        }

        if (packet.has("sequence")) {
            checkAndCollect(results,
                    runtime.packetOrder().check(playerId, packet.packetType(), (long) packet.num("sequence", 0)));
        }
        checkAndCollect(results,
                runtime.transactionSync().check(playerId, runtime.latencyCompensator()));

        runtime.fuse(playerId, type(), runtime.latencyCompensator().estimatedLatencyMs(), false)
                .ifPresent(results::add);
        return results;
    }

    @Override
    public void reset(String playerId) {
        runtime.clear(playerId);
        perPlayerPackets.remove(playerId);
    }

    private static boolean isMovementPacket(String type) {
        return type.contains("position") || type.contains("move") || type.contains("fly");
    }

    private static void checkAndCollect(List<DetectionResult> results, Optional<Evidence> evidence) {
        evidence.ifPresent(ev -> results.add(PacketIntelCheck.toResult(ev)));
    }

    static DetectionResult toResult(Evidence evidence) {
        return DetectionResult.violation(
                CheckType.PACKET,
                evidence.detectorId(),
                (int) Math.round(evidence.confidence() * 100),
                evidence.detail(),
                evidence.timestampMs());
    }
}
