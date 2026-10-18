package com.potatotv.pacc.detection.server.packet;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backtrack / FakeLag 检测（P-05，借鉴 Axiom 的包滞留与时序重放思路）。
 *
 * <p>两类行为分开判定，互不掩盖：
 * <ul>
 *   <li><b>FakeLag</b>：客户端故意压包再突发释放。表现为窗口内出现「多个 &lt;2ms 的密集到达」
 *       叠加「一次 &gt;800ms 的长间隔」。正常高延迟玩家的间隔是整体拉长，不会同时出现这两种形态。</li>
 *   <li><b>Backtrack</b>：重放旧位置或旧包。<b>位置回退</b>——当前坐标回到 10-60 个包之前的
 *       旧位置，而中间曾经离开过该点（不是原地抖动）；<b>序号回退</b>——序号比窗口内最大值
 *       倒退超过 {@value #SEQUENCE_REWIND_TOLERANCE}。</li>
 * </ul>
 *
 * <p>窗口只保留最近 {@value #WINDOW_SIZE} 个带坐标的移动包；纯旋转包不参与。所有判定都是
 * 纯内存计算，线程安全，不依赖客户端自报时间戳——服务端只信到达时间与包内坐标。
 */
public final class BacktrackAnalyzer {

    public static final String DETECTOR_ID = "backtrack-timing";

    /** 窗口容量（移动包数）。 */
    private static final int WINDOW_SIZE = 120;

    /** 密集到达阈值（ms）。 */
    private static final long BURST_INTERVAL_MS = 2L;

    /** 长间隔阈值（ms）。 */
    private static final long HOLD_GAP_MS = 800L;

    /** 触发 FakeLag 所需的最少密集到达数。 */
    private static final int BURST_MIN_COUNT = 3;

    /** 触发 FakeLag 所需的最小间隔样本数。 */
    private static final int MIN_INTERVALS = 12;

    /** 位置回退比对的最早/最晚包龄。 */
    private static final int REWIND_MIN_AGE = 10;
    private static final int REWIND_MAX_AGE = 60;

    /** 判定「回到旧位置」的距离容差（格）。 */
    private static final double REWIND_POSITION_EPS = 0.75;

    /** 判定「曾经离开」的最小距离（格）。 */
    private static final double REWIND_DEPART_DISTANCE = 2.0;

    /** 序号回退容差。 */
    private static final int SEQUENCE_REWIND_TOLERANCE = 20;

    private final ConcurrentHashMap<String, Deque<Sample>> windows = new ConcurrentHashMap<>();

    /**
     * 记录一个带坐标的移动包。
     *
     * @param sequence 包序号；平台不提供时传 {@link Integer#MIN_VALUE}
     */
    public void record(String playerId, long arrivalMs, int sequence,
                       double x, double y, double z) {
        if (playerId == null || Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return;
        }
        Deque<Sample> window = windows.computeIfAbsent(playerId, unused -> new ArrayDeque<>());
        synchronized (window) {
            if (window.size() >= WINDOW_SIZE) {
                window.pollFirst();
            }
            window.addLast(new Sample(arrivalMs, sequence, x, y, z));
        }
    }

    /** 评估当前窗口；证据不足返回空。 */
    public Optional<Evidence> check(String playerId) {
        Deque<Sample> window = windows.get(playerId);
        if (window == null) {
            return Optional.empty();
        }
        List<Sample> snapshot;
        synchronized (window) {
            snapshot = new ArrayList<>(window);
        }
        Optional<Evidence> fakeLag = checkFakeLag(snapshot);
        Optional<Evidence> backtrack = checkBacktrack(snapshot);
        if (fakeLag.isPresent() && backtrack.isPresent()) {
            // 同时命中时取置信度更高的一条；两条证据由融合层按层内 noisy-OR 处理
            return fakeLag.get().confidence() >= backtrack.get().confidence() ? fakeLag : backtrack;
        }
        return fakeLag.isPresent() ? fakeLag : backtrack;
    }

    public void reset(String playerId) {
        windows.remove(playerId);
    }

    private static Optional<Evidence> checkFakeLag(List<Sample> samples) {
        if (samples.size() < MIN_INTERVALS + 1) {
            return Optional.empty();
        }
        int bursts = 0;
        long maxGap = 0;
        for (int i = 1; i < samples.size(); i++) {
            long gap = samples.get(i).arrivalMs() - samples.get(i - 1).arrivalMs();
            if (gap <= BURST_INTERVAL_MS) {
                bursts++;
            }
            if (gap > maxGap) {
                maxGap = gap;
            }
        }
        if (bursts < BURST_MIN_COUNT || maxGap < HOLD_GAP_MS) {
            return Optional.empty();
        }
        double burstRatio = (double) bursts / (samples.size() - 1);
        double confidence = Math.min(0.95, 0.55 + 0.25 * Math.min(1.0, burstRatio * 4) +
                0.15 * Math.min(1.0, (double) maxGap / 2000.0));
        String detail = String.format(Locale.ROOT,
                "FakeLag 疑似 密集到达=%d 次 最长间隔=%dms 样本=%d", bursts, maxGap, samples.size());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, confidence, detail));
    }

    private static Optional<Evidence> checkBacktrack(List<Sample> samples) {
        if (samples.size() <= REWIND_MIN_AGE + 1) {
            return Optional.empty();
        }
        int last = samples.size() - 1;
        Sample current = samples.get(last);
        Sample previous = samples.get(last - 1);
        double stepDistance = distance(current, previous);

        int sequenceRegressions = 0;
        int maxSequence = Integer.MIN_VALUE;
        for (int i = 0; i < last; i++) {
            int seq = samples.get(i).sequence();
            if (seq != Integer.MIN_VALUE && seq > maxSequence) {
                maxSequence = seq;
            }
        }
        if (current.sequence() != Integer.MIN_VALUE
                && maxSequence != Integer.MIN_VALUE
                && current.sequence() < maxSequence - SEQUENCE_REWIND_TOLERANCE) {
            sequenceRegressions = 1;
        }

        boolean rewind = false;
        double departureDistance = 0;
        // 只有「突然跳回旧位置」才算：本包相对上一包跨了至少 2 格，且落点与 10-60 包前的
        // 某个位置重合，而中间曾经离开该落点 3 格以上。正常走路绕圈不会出现瞬移式回退。
        if (stepDistance >= REWIND_DEPART_DISTANCE) {
            int earliest = Math.max(0, last - REWIND_MAX_AGE);
            int latest = last - REWIND_MIN_AGE;
            for (int j = earliest; j <= latest && !rewind; j++) {
                Sample old = samples.get(j);
                if (distance(current, old) > REWIND_POSITION_EPS) {
                    continue;
                }
                for (int k = j + 1; k < last; k++) {
                    double departed = distance(samples.get(k), old);
                    if (departed >= REWIND_DEPART_DISTANCE) {
                        rewind = true;
                        departureDistance = Math.max(departureDistance, departed);
                        break;
                    }
                }
            }
        }
        if (!rewind && sequenceRegressions == 0) {
            return Optional.empty();
        }
        double confidence = Math.min(0.95, rewind ? 0.8 : 0.72);
        String detail = String.format(Locale.ROOT,
                "Backtrack 疑似 位置回退=%s 序号回退=%d 次 跳回距离=%.2f格 离开距离=%.2f格 样本=%d",
                rewind ? "是" : "否", sequenceRegressions, stepDistance, departureDistance, samples.size());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, confidence, detail));
    }

    private static double distance(Sample a, Sample b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record Sample(long arrivalMs, int sequence, double x, double y, double z) {
    }
}
