package com.potatotv.pacc.detection.server.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

/**
 * 攻击模式识别（技术文档 §7.4 攻击模式 + 目标切换的 KillAura / MultiAura 组合特征）。
 *
 * <p>两类特征：
 * <ul>
 *   <li>KillAura：攻击间隔序列的 GCD 模式 + 方差趋零（固定间隔连点）。</li>
 *   <li>MultiAura：窗口内目标数 &gt; 3 且目标轮换呈严格周期。</li>
 * </ul>
 *
 * <p>间隔需 ≥ {@value #MIN_INTERVALS} 个才做 KillAura 结论，避免小样本误判。
 */
public final class AttackPattern {

    public static final String DETECTOR_ID = "combat-attack-pattern";

    /** KillAura 判定所需的最小间隔样本数。 */
    public static final int MIN_INTERVALS = 10;

    /** MultiAura 目标轮换检测窗口大小。 */
    public static final int MULTI_TARGET_WINDOW = 12;

    /** KillAura 固定置信。 */
    private static final double KILLAURA_CONFIDENCE = 0.8;

    /** MultiAura 固定置信。 */
    private static final double MULTIAURA_CONFIDENCE = 0.7;

    /** GCD 模式占比阈值（同 ClickProfile 口径）。 */
    private static final double GCD_RATIO_THRESHOLD = 0.8;

    /** 间隔变异系数阈值：std/mean 低于此视为「方差极小」。 */
    private static final double CV_THRESHOLD = 0.05;

    private record Attack(long timestampMs, String targetId) {
    }

    /** 攻击记录窗口。 */
    private static final int WINDOW_SIZE = 32;

    private final ConcurrentHashMap<String, Deque<Attack>> windows = new ConcurrentHashMap<>();

    /** 记录一次攻击。 */
    public void recordAttack(String playerId, long timestampMs, String targetId) {
        Deque<Attack> window = windows.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        synchronized (window) {
            if (window.size() >= WINDOW_SIZE) {
                window.pollFirst();
            }
            window.addLast(new Attack(timestampMs, targetId));
        }
    }

    /** 评估当前窗口：KillAura 优先，其次 MultiAura。 */
    public Optional<Evidence> check(String playerId) {
        Deque<Attack> window = windows.get(playerId);
        if (window == null) {
            return Optional.empty();
        }
        List<Attack> snapshot;
        synchronized (window) {
            snapshot = List.copyOf(window);
        }
        Optional<Evidence> killAura = checkKillAura(snapshot);
        if (killAura.isPresent()) {
            return killAura;
        }
        return checkMultiAura(snapshot);
    }

    /** KillAura：间隔 GCD 模式明显且方差极小（固定间隔机械连点）。 */
    private Optional<Evidence> checkKillAura(List<Attack> attacks) {
        long[] intervals = intervals(attacks);
        if (intervals.length < MIN_INTERVALS) {
            return Optional.empty();
        }
        double mean = mean(intervals);
        if (mean <= 0) {
            return Optional.empty();
        }
        double cv = Math.sqrt(variance(intervals, mean)) / mean;
        double gcdRatio = gcdPatternRatio(intervals);
        if (gcdRatio > GCD_RATIO_THRESHOLD && cv < CV_THRESHOLD) {
            String detail = String.format(Locale.ROOT,
                    "KillAura 间隔均值=%.1fms 变异系数=%.3f GCD模式占比=%.2f",
                    mean, cv, gcdRatio);
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, KILLAURA_CONFIDENCE, detail));
        }
        return Optional.empty();
    }

    /** MultiAura：窗口内目标数 &gt; 3 且轮换呈严格周期。 */
    private Optional<Evidence> checkMultiAura(List<Attack> attacks) {
        int from = Math.max(0, attacks.size() - MULTI_TARGET_WINDOW);
        List<Attack> recent = attacks.subList(from, attacks.size());
        if (recent.size() < 8) {
            return Optional.empty();
        }
        Set<String> targets = new HashSet<>();
        for (Attack attack : recent) {
            targets.add(attack.targetId());
        }
        if (targets.size() <= 3) {
            return Optional.empty();
        }
        int period = cyclicPeriod(recent);
        if (period > 0) {
            String detail = String.format(Locale.ROOT,
                    "MultiAura 目标数=%d 轮换周期=%d 窗口=%d",
                    targets.size(), period, recent.size());
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, MULTIAURA_CONFIDENCE, detail));
        }
        return Optional.empty();
    }

    /** 清空玩家状态。 */
    public void reset(String playerId) {
        windows.remove(playerId);
    }

    /**
     * 相邻攻击间隔的两两 GCD 中大于 20ms 的占比
     * （ClickProfile.gcdPatternRatio 是 private，此处按同一口径自实现，供 KillAura 与测试使用）。
     */
    public static double gcdPatternRatio(long[] intervals) {
        if (intervals.length < 2) {
            return 0.0;
        }
        int hits = 0;
        int pairs = 0;
        for (int i = 0; i < intervals.length; i++) {
            for (int j = i + 1; j < intervals.length; j++) {
                pairs++;
                if (gcd(intervals[i], intervals[j]) > 20) {
                    hits++;
                }
            }
        }
        return pairs == 0 ? 0.0 : (double) hits / pairs;
    }

    private static int cyclicPeriod(List<Attack> attacks) {
        int n = attacks.size();
        for (int period = 2; period <= 6 && period * 2 <= n; period++) {
            int comparisons = n - period;
            int matches = 0;
            for (int i = 0; i < comparisons; i++) {
                if (attacks.get(i).targetId().equals(attacks.get(i + period).targetId())) {
                    matches++;
                }
            }
            if ((double) matches / comparisons >= 0.9) {
                return period;
            }
        }
        return -1;
    }

    private static long[] intervals(List<Attack> attacks) {
        List<Long> diffs = new ArrayList<>(attacks.size());
        for (int i = 1; i < attacks.size(); i++) {
            long diff = attacks.get(i).timestampMs() - attacks.get(i - 1).timestampMs();
            if (diff > 0) {
                diffs.add(diff);
            }
        }
        long[] result = new long[diffs.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = diffs.get(i);
        }
        return result;
    }

    private static double mean(long[] values) {
        double sum = 0;
        for (long v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double variance(long[] values, double mean) {
        double sum = 0;
        for (long v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return sum / values.length;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
