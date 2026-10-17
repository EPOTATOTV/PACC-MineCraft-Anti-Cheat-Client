package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.server.perf.PerformanceCalibrator;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.check.TickAware;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.packet.PacketTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据包 intel 检查（技术文档 §5.2 packet 行 / §九 PingSpoof·Disabler 反制）。
 *
 * <p>平台适配层只给归一化字段视图（{@link PacketView}），本检查按包类型分流：
 * 移动包喂包时序（Timer/Blink），事务 / Pong 包回填延迟补偿（TransactionSync），
 * 品牌包做客户端一致性校验，带序号的包做乱序检测。
 *
 * <p>Timer 的基准仍是客户端标称的 50ms（客户端发包频率不随服务端 TPS 变化），但判定阈值会
 * 按实测 TPS 与延迟放宽——卡服时包成批到达，平均间隔被压小，不放宽就是整片误报
 * （§5.3 U-05）。低 TPS 时置信度再乘一次可靠性系数，宁可少报。
 */
public final class PacketIntelCheck implements ServerCheck, TickAware {

    /** 每 N 个移动包发一次事务探测（延迟补偿，§7.2 transaction 机制）。 */
    static final int TRANSACTION_EVERY = 20;

    private final IntelRuntime runtime;
    private final ConcurrentHashMap<String, AtomicInteger> perPlayerPackets = new ConcurrentHashMap<>();

    public PacketIntelCheck(IntelRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public CheckType type() {
        return CheckType.PACKET;
    }

    @Override
    public void onTick(long nowMs) {
        runtime.calibrator().recordTick(nowMs);
    }

    @Override
    public List<DetectionResult> onPacket(PacketView packet) {
        List<DetectionResult> results = new ArrayList<>();
        String type = packet.packetType() == null ? "" : packet.packetType().toLowerCase(Locale.ROOT);
        String playerId = packet.playerId();

        if (isMovementPacket(type)) {
            runtime.packetTiming().recordMovement(playerId, packet.timestampMs());
            // Timer / Blink：基准取客户端标称刻长，阈值按 TPS 与延迟放宽（§5.3 U-05）
            long latencyMs = runtime.latencyCompensator().estimatedLatencyMs();
            checkAndCollect(results, runtime.packetTiming().check(
                    playerId,
                    Math.round(PerformanceCalibrator.NOMINAL_TICK_MS),
                    runtime.calibrator().thresholdScale(latencyMs)));
            // 事务探测只在平台真能发包时做：发不出去就没有 ack，记账只会把正常玩家判成 Disabler
            if (runtime.transactionsSupported()
                    && perPlayerPackets.computeIfAbsent(playerId, unused -> new AtomicInteger())
                    .incrementAndGet() % TRANSACTION_EVERY == 0) {
                runtime.requestTransaction(playerId);
            }
        } else if (isTransactionAck(type)) {
            String idField = packet.str(PacketTypes.FIELD_TRANSACTION_ID);
            if (idField != null) {
                try {
                    runtime.latencyCompensator().ack(Integer.parseInt(idField), packet.timestampMs());
                } catch (NumberFormatException ignored) {
                    // 字段不是数字，忽略
                }
            }
        } else if (type.contains("brand")) {
            checkAndCollect(results,
                    runtime.brandValidator().check(playerId, packet.str("brand"), List.of()));
        }

        if (packet.has(PacketTypes.FIELD_SEQUENCE)) {
            checkAndCollect(results,
                    runtime.packetOrder().check(playerId, packet.packetType(),
                            (long) packet.num(PacketTypes.FIELD_SEQUENCE, 0)));
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

    /** 事务 ack 的三种形态：旧版 WindowConfirmation、新版 Pong，以及合成占位类型。 */
    private static boolean isTransactionAck(String type) {
        return type.equalsIgnoreCase(PacketTypes.TRANSACTION)
                || type.equalsIgnoreCase(PacketTypes.PONG)
                || type.equalsIgnoreCase(PacketTypes.WINDOW_CONFIRMATION);
    }

    private static boolean isMovementPacket(String type) {
        return type.contains("position") || type.contains("move") || type.contains("fly");
    }

    private void checkAndCollect(List<DetectionResult> results, Optional<Evidence> evidence) {
        evidence.ifPresent(ev -> results.add(toResult(withReliability(ev))));
    }

    /** 按当前 TPS 的可靠性系数缩放置信度；健康区间原样返回，避免无谓的对象分配。 */
    private Evidence withReliability(Evidence evidence) {
        double reliability = runtime.calibrator().reliabilityFactor();
        if (reliability >= 1.0) {
            return evidence;
        }
        return new Evidence(evidence.detectorId(), evidence.layer(),
                Math.max(0.0, evidence.confidence() * reliability),
                evidence.detail(), evidence.timestampMs());
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
