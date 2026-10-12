package com.potatotv.pacc.detection.server.combat;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.server.prediction.Position;

/**
 * 击退预测（技术文档 §7.3：「击退预测准确率 99.99%」Grim 口径）。
 *
 * <p>服务端施加击退时记录预测位移幅值，玩家下一状态回来后比较实际位移：
 * 幅值偏差 &gt; {@value #DEVIATION_THRESHOLD} 格判定为 AntiKnockback。
 * 签名未含击退朝向，故按位移幅值而非向量比较；方向核对由调用方接入真实协议时补充。
 *
 * <p>误报抑制（文档 §5.2）：击退免疫 / 冻结 / 载具等豁免场景由调用方直接不调用
 * {@link #recordKnockback} 实现；网络抖动单次偏差用「连续 2 次才出证」抑制。
 */
public final class KnockbackPredictor {

    public static final String DETECTOR_ID = "combat-knockback";

    /** 幅值偏差阈值（格）。 */
    public static final double DEVIATION_THRESHOLD = 1.5;

    /** 连续偏差次数达到该值才出证。 */
    public static final int REQUIRED_CONSECUTIVE = 2;

    /** AntiKnockback 固定置信（偏差远超正常网络抖动）。 */
    private static final double CONFIDENCE = 0.9;

    private record Pending(Position from, double expectedMagnitude, long recordedAtMs) {
    }

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> strikes = new ConcurrentHashMap<>();

    /**
     * 服务端施加击退时调用。预测单 tick 位移（MC 口径）：
     * 水平 ≈ 0.4 × strength，垂直 ≈ 0.36 + 0.04 × strength。
     */
    public void recordKnockback(String playerId, Position posBefore, double knockbackStrength, long timestampMs) {
        double horizontal = 0.4 * knockbackStrength;
        double vertical = 0.36 + 0.04 * knockbackStrength;
        double magnitude = Math.sqrt(horizontal * horizontal + vertical * vertical);
        pending.put(playerId, new Pending(posBefore, magnitude, timestampMs));
    }

    /**
     * 击退后的实际位置核对：偏差超阈值记一次 strike，连续 {@value #REQUIRED_CONSECUTIVE} 次出证；
     * 偏差正常则清零 strike。
     */
    public Optional<Evidence> check(String playerId, Position actualAfterTicks, boolean wasKnockedBack) {
        Pending recorded = pending.remove(playerId);
        if (recorded == null || !wasKnockedBack) {
            return Optional.empty();
        }
        double actualMagnitude = recorded.from().distanceTo(actualAfterTicks);
        double deviation = Math.abs(actualMagnitude - recorded.expectedMagnitude());
        if (deviation <= DEVIATION_THRESHOLD) {
            strikes.remove(playerId);
            return Optional.empty();
        }
        int streak = strikes
                .computeIfAbsent(playerId, k -> new AtomicInteger())
                .incrementAndGet();
        if (streak < REQUIRED_CONSECUTIVE) {
            return Optional.empty();
        }
        String detail = String.format(Locale.ROOT,
                "AntiKnockback 预测位移=%.3f 实际位移=%.3f 偏差=%.3f 阈值=%.1f 连续第%d次",
                recorded.expectedMagnitude(), actualMagnitude, deviation, DEVIATION_THRESHOLD, streak);
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, CONFIDENCE, detail));
    }

    /** 清空玩家状态。 */
    public void reset(String playerId) {
        pending.remove(playerId);
        strikes.remove(playerId);
    }
}
