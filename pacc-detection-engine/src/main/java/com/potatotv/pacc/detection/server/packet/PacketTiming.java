package com.potatotv.pacc.detection.server.packet;

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
 * 移动包计时检测（技术文档 §九 Timer / Blink）。
 *
 * <p>Timer：最近 100 个移动包的平均间隔推算发包速率。rate = serverTickMs / avgInterval
 * （服务器 20TPS 即 50ms/tick）；serverTickMs 由调用方传实际 tick 时长做 TPS 波动归一化，
 * rate &gt; 1.005 出证（Grim 阈值 1.005：留 0.5% 给客户端帧计时误差）。
 *
 * <p>Blink：到达间隔的中位数 m 与最大间隔 max，max &gt; 8·m 且 max &gt; 2000ms
 * 视为间隔突变（攒包后突发释放）。位置突跳核对不在本类做——只出「间隔突变」证据，
 * 与位移证据的合并由上层（CorrelationEngine / 融合层）完成。
 *
 * <p>线程安全：ConcurrentHashMap + synchronized 窗口，纯内存计算。
 */
public final class PacketTiming {

    public static final String DETECTOR_ID = "packet-timing";

    /** Timer 阈值（Grim 口径）。 */
    public static final double TIMER_THRESHOLD = 1.005;

    /** 滚动窗口容量（文档口径：最近 100 包）。 */
    private static final int WINDOW_SIZE = 100;

    /** Timer 判定所需最小样本数（冷启动不判）。 */
    private static final int MIN_TIMER_SAMPLES = 20;

    /** Blink 判定所需最小间隔数。 */
    private static final int MIN_BLINK_INTERVALS = 10;

    /** Blink：最大间隔须超过中位数的倍数。 */
    private static final double BLINK_MEDIAN_MULTIPLE = 8.0;

    /** Blink：最大间隔绝对下限（ms）。 */
    private static final long BLINK_MIN_GAP_MS = 2000;

    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /** 记录一个移动包的到达时间。 */
    public void recordMovement(String playerId, long timestampMs) {
        Deque<Long> window = windows.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        synchronized (window) {
            if (window.size() >= WINDOW_SIZE) {
                window.pollFirst();
            }
            window.addLast(timestampMs);
        }
    }

    /** 综合检查：Timer 优先，未触发再看 Blink（两层各自独立出证，见 checkTimer/checkBlink）。 */
    public Optional<Evidence> check(String playerId, long serverTickMs) {
        return check(playerId, serverTickMs, 1.0);
    }

    /**
     * 综合检查，{@code thresholdScale} 放宽 Timer 阈值（≥1.0）。高延迟 / 低 TPS 下包成批到达，
     * 平均间隔会被压小，用放宽系数把门槛抬上去，避免整片误报（§5.3 U-05）。
     */
    public Optional<Evidence> check(String playerId, long serverTickMs, double thresholdScale) {
        Optional<Evidence> timer = checkTimer(playerId, serverTickMs, thresholdScale);
        if (timer.isPresent()) {
            return timer;
        }
        return checkBlink(playerId);
    }

    /** Timer 检测：发包速率超过服务器 tick 速率 0.5% 即可疑。 */
    public Optional<Evidence> checkTimer(String playerId, long serverTickMs) {
        return checkTimer(playerId, serverTickMs, 1.0);
    }

    /**
     * Timer 检测，阈值按 {@code thresholdScale} 放宽。
     *
     * <p>注意 {@code serverTickMs} 仍是**客户端标称**的 50ms，不是实测服务端刻长：客户端发包
     * 频率不随服务端 TPS 变化，拿实测刻长做分母会把卡服时的正常玩家全判成 Timer。
     */
    public Optional<Evidence> checkTimer(String playerId, long serverTickMs, double thresholdScale) {
        List<Long> intervals = positiveIntervals(playerId);
        if (intervals.size() < MIN_TIMER_SAMPLES) {
            return Optional.empty();
        }
        double sum = 0;
        for (long interval : intervals) {
            sum += interval;
        }
        double avg = sum / intervals.size();
        if (avg <= 0) {
            return Optional.empty();
        }
        double threshold = TIMER_THRESHOLD * Math.max(1.0, thresholdScale);
        double rate = serverTickMs / avg;
        if (rate <= threshold) {
            return Optional.empty();
        }
        double confidence = Math.min(1.0, 0.5 + rate - threshold);
        String detail = String.format(Locale.ROOT,
                "Timer rate=%.4f 阈值=%.4f 平均间隔=%.2fms tick=%dms 样本=%d",
                rate, threshold, avg, serverTickMs, intervals.size());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, confidence, detail));
    }

    /** Blink 检测：间隔突变（攒包突发）。位置突跳由上层合并。 */
    public Optional<Evidence> checkBlink(String playerId) {
        List<Long> intervals = positiveIntervals(playerId);
        if (intervals.size() < MIN_BLINK_INTERVALS) {
            return Optional.empty();
        }
        List<Long> sorted = new ArrayList<>(intervals);
        sorted.sort(Long::compare);
        int n = sorted.size();
        long median = (n % 2 == 1)
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        long max = sorted.get(n - 1);
        if (median <= 0 || max <= BLINK_MIN_GAP_MS || max <= BLINK_MEDIAN_MULTIPLE * median) {
            return Optional.empty();
        }
        String detail = String.format(Locale.ROOT,
                "Blink 疑似 间隔中位数=%dms 最大间隔=%dms（%.1f×中位数），位置跳跃由上层合并",
                median, max, (double) max / median);
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, 0.5, detail));
    }

    /** 清空玩家窗口。 */
    public void reset(String playerId) {
        windows.remove(playerId);
    }

    private List<Long> positiveIntervals(String playerId) {
        Deque<Long> window = windows.get(playerId);
        if (window == null) {
            return List.of();
        }
        List<Long> snapshot;
        synchronized (window) {
            snapshot = new ArrayList<>(window);
        }
        List<Long> intervals = new ArrayList<>(snapshot.size());
        for (int i = 1; i < snapshot.size(); i++) {
            long diff = snapshot.get(i) - snapshot.get(i - 1);
            if (diff > 0) {
                intervals.add(diff);
            }
        }
        return intervals;
    }
}
