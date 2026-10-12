package com.potatotv.pacc.detection.server.packet;

import java.util.Locale;
import java.util.Optional;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.server.prediction.LatencyCompensator;

/**
 * 事务同步检测（技术文档 §7.2 transaction 机制 + §九 Disabler）。
 *
 * <p>对接 {@link LatencyCompensator}：
 * <ul>
 *   <li>发出 ≥ 10 个事务且 ack 率 &lt; 50% → 客户端在批量丢弃事务（Disabler 特征）0.85；</li>
 *   <li>有 ack 但延迟估计 &gt; 10s → PingSpoof 疑似（故意压 ack 时间）0.6。</li>
 * </ul>
 */
public final class TransactionSync {

    public static final String DETECTOR_ID = "packet-transaction";

    /** 评估 ack 率所需的最小事务数。 */
    public static final int MIN_SENT = 10;

    /** ack 率低于该值视为丢弃事务。 */
    public static final double ACK_RATE_THRESHOLD = 0.5;

    /** 延迟估计超过该值视为 PingSpoof（ms）。 */
    public static final long PING_SPOOF_LATENCY_MS = 10_000;

    /** Disabler 固定置信。 */
    private static final double DISABLER_CONFIDENCE = 0.85;

    /** PingSpoof 固定置信。 */
    private static final double PING_SPOOF_CONFIDENCE = 0.6;

    /**
     * 检查一名玩家的事务同步状况。compensator 为该玩家专属实例
     * （每玩家一个，生命周期与会话一致）。
     */
    public Optional<Evidence> check(String playerId, LatencyCompensator compensator) {
        if (compensator == null) {
            return Optional.empty();
        }
        int sent = compensator.sentCount();
        int acked = compensator.ackedCount();
        if (sent >= MIN_SENT) {
            double ackRate = (double) acked / sent;
            if (ackRate < ACK_RATE_THRESHOLD) {
                String detail = String.format(Locale.ROOT,
                        "Disabler 疑似 事务ack率=%.2f（%d/%d）低于阈值%.2f",
                        ackRate, acked, sent, ACK_RATE_THRESHOLD);
                return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, DISABLER_CONFIDENCE, detail));
            }
        }
        long latency = compensator.estimatedLatencyMs();
        if (latency > PING_SPOOF_LATENCY_MS) {
            String detail = String.format(Locale.ROOT,
                    "PingSpoof 疑似 事务确认延迟中位数=%dms 超过%dms",
                    latency, PING_SPOOF_LATENCY_MS);
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, PING_SPOOF_CONFIDENCE, detail));
        }
        return Optional.empty();
    }
}
