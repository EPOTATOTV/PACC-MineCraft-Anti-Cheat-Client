package com.potatotv.pacc.detection.server.combat;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.server.prediction.Position;

/**
 * 攻击距离检测（技术文档 §7.3 战斗检测强化）。
 *
 * <p>阈值口径（GrimAC）：生存模式攻击可达上限 3.0 格，+0.01 浮点容忍 → 3.01。
 * 距离为双方坐标的欧氏距离（命中箱半径差异由阈值口径吸收）。
 *
 * <p>距离 &gt; 6 格不判：大概率是传送 / 换服数据延迟，交给上层处理，
 * 同时中断连续违规链避免旧状态污染。
 *
 * <p>线程安全：每玩家违规计数用 ConcurrentHashMap + AtomicInteger，纯内存计算不阻塞主线程。
 */
public final class ReachCheck {

    public static final String DETECTOR_ID = "combat-reach";

    /** 攻击距离阈值：3.0 可达上限 + 0.01 浮点容忍（Grim 口径）。 */
    public static final double MAX_REACH = 3.01;

    /** 超过该距离视为传送 / 数据异常样本，不参与判定。 */
    public static final double MAX_PLAUSIBLE_DISTANCE = 6.0;

    /** 基础置信。 */
    private static final double BASE_CONFIDENCE = 0.6;

    /** 连续违规每次追加的置信。 */
    private static final double STREAK_STEP = 0.05;

    private final ConcurrentHashMap<String, AtomicInteger> streaks = new ConcurrentHashMap<>();

    /**
     * 一次攻击判定：距离超出 3.01 即出证，连续违规按次放大置信。
     *
     * @param victimOnGround 仅进入 detail 供上层误报抑制参考（击退在空中的距离抖动更大）
     */
    public Optional<Evidence> check(String attackerId, Position attackerPos, Position victimPos,
                                    boolean victimOnGround, long timestampMs) {
        double distance = attackerPos.distanceTo(victimPos);
        if (distance > MAX_PLAUSIBLE_DISTANCE) {
            streaks.remove(attackerId);
            return Optional.empty();
        }
        if (distance <= MAX_REACH) {
            return Optional.empty();
        }
        int streak = streaks
                .computeIfAbsent(attackerId, k -> new AtomicInteger())
                .incrementAndGet();
        double base = BASE_CONFIDENCE + (distance - MAX_REACH);
        double confidence = Math.min(1.0, base + STREAK_STEP * streak);
        String detail = String.format(Locale.ROOT,
                "reach=%.3f 阈值=%.2f 连续第%d次 victimOnGround=%b",
                distance, MAX_REACH, streak, victimOnGround);
        return Optional.of(new Evidence(DETECTOR_ID, Layer.BEHAVIOR, confidence, detail, timestampMs));
    }

    /** 清空玩家连续违规链（换目标 / 死亡重生时调用）。 */
    public void reset(String playerId) {
        streaks.remove(playerId);
    }
}
