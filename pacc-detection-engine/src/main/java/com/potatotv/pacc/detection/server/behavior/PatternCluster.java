package com.potatotv.pacc.detection.server.behavior;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

/**
 * 无监督行为模式聚类（技术文档 §7.4 模式聚类）。
 *
 * <p>不做真实聚类训练，用两个轻量启发式近似「自动化行为」语义：
 * <ul>
 *   <li>周期性检测：最近 60 tick 的动作签名序列（挖/攻/移三态编码）呈严格周期
 *       （自相关峰值 ≥ 0.9，周期 4-40 tick）→ AutoFarm 类自动化证据 0.7。
 *       自相关语义：签名序列与自身平移 p 的匹配率；纯移动流（签名恒定）无动作语义，
 *       跳过周期检测交给直线度判定。</li>
 *   <li>寻路直线度：连续移动期间 yaw 每 tick 变化都在 ±0.5° 内且持续 &gt; 10s
 *       → Baritone 轨迹特征 0.6。</li>
 * </ul>
 *
 * <p>线程安全：ConcurrentHashMap + synchronized 窗口；纯内存计算。
 */
public final class PatternCluster {

    public static final String DETECTOR_ID = "behavior-cluster";

    /** 动作签名编码。 */
    private static final int SIG_IDLE = 0;
    private static final int SIG_DIG = 1;
    private static final int SIG_ATTACK = 2;
    private static final int SIG_MOVE = 3;

    /** 观察窗口（tick 数）。 */
    private static final int WINDOW_SIZE = 60;

    /** 周期检测最小序列长度。 */
    private static final int MIN_SEQUENCE = 20;

    /** 周期搜索范围（tick）。 */
    private static final int MIN_PERIOD = 4;
    private static final int MAX_PERIOD = 40;

    /** 自相关匹配率阈值。 */
    private static final double AUTOCORR_THRESHOLD = 0.9;

    /** 非空闲动作占比下限（防止全空闲序列平凡周期）。 */
    private static final double MIN_ACTIVE_RATIO = 0.3;

    /** 视为「移动」的最小速度（格/tick）。 */
    private static final double MOVE_SPEED_THRESHOLD = 0.03;

    /** Baritone 判定：每 tick yaw 变化上限（度）。 */
    private static final double BARITONE_YAW_DELTA = 0.5;

    /** Baritone 判定：直线移动最短持续时间（ms）。 */
    private static final long BARITONE_MIN_DURATION_MS = 10_000;

    /** 视为移动的最小速度（Baritone 判定用）。 */
    private static final double BARITONE_SPEED_THRESHOLD = 0.05;

    private record TickRecord(double speed, double yawDelta, boolean digging, boolean attacking, long timestampMs) {
    }

    /**
     * 直线移动运行状态：10s 直线远超 60 tick 窗口（3s），必须跨窗口累计。
     * record 时增量维护，check 时读取，全部在对象锁内完成。
     */
    private static final class StraightRun {
        long runStartMs = -1;
        long runLastMs = -1;
        long bestDurationMs = -1;

        void extend(long timestampMs) {
            if (runStartMs < 0) {
                runStartMs = timestampMs;
            }
            runLastMs = timestampMs;
        }

        void breakRun() {
            if (runStartMs >= 0) {
                long duration = runLastMs - runStartMs;
                if (duration > bestDurationMs) {
                    bestDurationMs = duration;
                }
                runStartMs = -1;
                runLastMs = -1;
            }
        }
    }

    private final ConcurrentHashMap<String, Deque<TickRecord>> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StraightRun> straightRuns = new ConcurrentHashMap<>();

    /** 记录一个 tick 的行为采样。 */
    public void record(String playerId, double speed, double yawDelta,
                       boolean digging, boolean attacking, long timestampMs) {
        Deque<TickRecord> window = windows.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        synchronized (window) {
            if (window.size() >= WINDOW_SIZE) {
                window.pollFirst();
            }
            window.addLast(new TickRecord(speed, yawDelta, digging, attacking, timestampMs));
        }
        boolean straightMoving = speed > BARITONE_SPEED_THRESHOLD
                && Math.abs(yawDelta) <= BARITONE_YAW_DELTA;
        StraightRun run = straightRuns.computeIfAbsent(playerId, k -> new StraightRun());
        synchronized (run) {
            if (straightMoving) {
                run.extend(timestampMs);
            } else {
                run.breakRun();
            }
        }
    }

    /** 评估当前窗口：周期性（AutoFarm）优先，其次直线度（Baritone）。 */
    public Optional<Evidence> check(String playerId) {
        Deque<TickRecord> window = windows.get(playerId);
        if (window == null) {
            return Optional.empty();
        }
        List<TickRecord> snapshot;
        synchronized (window) {
            snapshot = new ArrayList<>(window);
        }
        Optional<Evidence> periodic = checkPeriodic(snapshot);
        if (periodic.isPresent()) {
            return periodic;
        }
        return checkStraightLine(playerId);
    }

    /** 清空玩家窗口。 */
    public void reset(String playerId) {
        windows.remove(playerId);
        straightRuns.remove(playerId);
    }

    /** 周期检测：动作签名序列的自相关峰值 ≥ 0.9 → AutoFarm 类自动化。 */
    private Optional<Evidence> checkPeriodic(List<TickRecord> records) {
        int n = records.size();
        if (n < MIN_SEQUENCE) {
            return Optional.empty();
        }
        int[] signatures = new int[n];
        int active = 0;
        for (int i = 0; i < n; i++) {
            signatures[i] = signature(records.get(i));
            if (signatures[i] != SIG_IDLE) {
                active++;
            }
        }
        // 纯移动流（每个 tick 都在移动）没有「动作节奏」语义，交给直线度判定；
        // 活跃占比过低（几乎全空闲）的平凡周期不判
        if (active >= n || active < (int) (MIN_ACTIVE_RATIO * n)) {
            return Optional.empty();
        }
        for (int period = MIN_PERIOD; period <= MAX_PERIOD; period++) {
            int comparisons = n - period;
            if (comparisons < MIN_SEQUENCE) {
                break;
            }
            int matches = 0;
            for (int i = 0; i < comparisons; i++) {
                if (signatures[i] == signatures[i + period]) {
                    matches++;
                }
            }
            double correlation = (double) matches / comparisons;
            if (correlation >= AUTOCORR_THRESHOLD) {
                String detail = String.format(Locale.ROOT,
                        "自动化周期特征 周期=%dtick 自相关=%.2f 活跃占比=%.2f",
                        period, correlation, (double) active / n);
                return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, 0.7, detail));
            }
        }
        return Optional.empty();
    }

    /** 直线度检测：恒定朝向连续移动 &gt; 10s → Baritone 轨迹特征（运行时长跨窗口累计）。 */
    private Optional<Evidence> checkStraightLine(String playerId) {
        StraightRun run = straightRuns.get(playerId);
        if (run == null) {
            return Optional.empty();
        }
        long best;
        synchronized (run) {
            best = Math.max(run.bestDurationMs,
                    run.runStartMs >= 0 ? run.runLastMs - run.runStartMs : -1);
        }
        if (best <= BARITONE_MIN_DURATION_MS) {
            return Optional.empty();
        }
        String detail = String.format(Locale.ROOT,
                "Baritone 轨迹特征 直线移动持续=%dms 阈值=%dms yaw抖动≤%.1f°/tick",
                best, BARITONE_MIN_DURATION_MS, BARITONE_YAW_DELTA);
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, 0.6, detail));
    }

    private static int signature(TickRecord record) {
        if (record.digging()) {
            return SIG_DIG;
        }
        if (record.attacking()) {
            return SIG_ATTACK;
        }
        return record.speed() > MOVE_SPEED_THRESHOLD ? SIG_MOVE : SIG_IDLE;
    }
}
