package com.potatotv.pacc.detection.server.perf;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 性能自适应阈值校准器（技术文档 §5.3 U-05）。
 *
 * <p>要解决的问题：Timer / Blink 这类包时序检测假定「客户端稳定每 50ms 发一个移动包」。
 * 服务器卡顿或链路拥塞时，包会在网卡侧堆积后成批到达，平均间隔被压到 50ms 以下，
 * {@code rate = 50 / avgInterval} 随之虚高——正常玩家被整片判成 Timer。高延迟会话同理，
 * Blink 的「间隔突变」也会被网络抖动触发。
 *
 * <p>所以校准器不改变「客户端标称 50ms 一刻」这个基准（客户端发包频率不随服务端 TPS 变），
 * 而是提供两件事：
 * <ul>
 *   <li>{@link #thresholdScale(long)} 给出阈值放宽系数——延迟越高、TPS 越低，系数越大，
 *       把判定门槛往上抬，宁可漏判也不误伤；</li>
 *   <li>{@link #reliabilityFactor()} 在 TPS 掉到 15 以下时给置信度降权，低吞吐环境下少报。</li>
 * </ul>
 *
 * <p>{@link #effectiveTickMs()} 只用于诊断与日志（报告实测刻长），不参与 Timer 的速率计算。
 * 没有样本时全部退回标称值，行为与引入本类之前一致。
 *
 * <p>线程安全：刻样本用 {@code synchronized} 守护的双端队列，读写都在纳秒级。
 */
public final class PerformanceCalibrator {

    /** 标称刻长：20 TPS。 */
    public static final double NOMINAL_TICK_MS = 50.0;

    /** 参与统计的最近刻数（约 2 秒）。 */
    static final int WINDOW = 40;

    /** 判定为「刻长可信」所需的最小样本数。 */
    static final int MIN_SAMPLES = 5;

    /** 实测刻长的夹取区间：低于 20ms 说明有多个任务在同刻被唤醒，高于 250ms 说明已严重卡顿。 */
    static final double MIN_TICK_MS = 20.0;
    static final double MAX_TICK_MS = 250.0;

    /** TPS 低于该值开始降权。 */
    public static final double DEGRADED_TPS = 15.0;

    /** TPS 不低于该值时视为健康。 */
    public static final double HEALTHY_TPS = 18.0;

    /** 降权下限：低于 {@link #DEGRADED_TPS} 时置信度一律打到这个系数。 */
    static final double MIN_RELIABILITY = 0.5;

    /** 高延迟会话的阈值放宽上限（300ms 及以上）。 */
    public static final double HIGH_LATENCY_MS = 300.0;

    /** 正常延迟上界：不超过该值不放宽阈值。 */
    public static final double NORMAL_LATENCY_MS = 50.0;

    /** 延迟放宽系数的上限。 */
    static final double MAX_LATENCY_FACTOR = 2.0;

    /** 低 TPS 时在延迟放宽之上额外叠加的余量。 */
    static final double LOW_TPS_THRESHOLD_BONUS = 0.5;

    /** 阈值放宽系数的总上限，防止卡到极点时阈值失效。 */
    static final double MAX_THRESHOLD_SCALE = 3.0;

    private final Deque<Long> tickIntervals = new ArrayDeque<>(WINDOW);
    private volatile long lastTickMs = -1L;
    private volatile double tps = 20.0;

    /** 记录一次服务端刻（由平台适配层按刻调用）。 */
    public void recordTick(long nowMs) {
        long last = lastTickMs;
        lastTickMs = nowMs;
        if (last <= 0 || nowMs <= last) {
            return;
        }
        long interval = nowMs - last;
        // 单次间隔超过 1 秒通常是挂起 / 断点 / GC 长停顿，不代表刻长，丢掉不污染窗口
        if (interval > 1000) {
            return;
        }
        synchronized (tickIntervals) {
            if (tickIntervals.size() >= WINDOW) {
                tickIntervals.pollFirst();
            }
            tickIntervals.addLast(interval);
        }
        double avg = averageInterval();
        tps = avg <= 0 ? 20.0 : Math.min(20.0, 1000.0 / avg);
    }

    /** 实测平均刻长（毫秒）；样本不足返回标称 {@value #NOMINAL_TICK_MS}。 */
    public double effectiveTickMs() {
        if (sampleCount() < MIN_SAMPLES) {
            return NOMINAL_TICK_MS;
        }
        double avg = averageInterval();
        if (avg <= 0) {
            return NOMINAL_TICK_MS;
        }
        return Math.max(MIN_TICK_MS, Math.min(MAX_TICK_MS, avg));
    }

    /** 最近窗口估算的 TPS（无样本时为 20.0）。 */
    public double tps() {
        return tps;
    }

    /** 服务端是否处于低吞吐状态（TPS < {@value #DEGRADED_TPS}）。 */
    public boolean lowThroughput() {
        return tps < DEGRADED_TPS;
    }

    /**
     * 可靠性系数：健康区间返回 1.0，TPS 从 {@value #HEALTHY_TPS} 掉到 {@value #DEGRADED_TPS}
     * 之间线性下降，低于下界固定 {@value #MIN_RELIABILITY}。用它乘检测置信度，避免卡服期间刷屏。
     */
    public double reliabilityFactor() {
        double current = tps;
        if (current >= HEALTHY_TPS) {
            return 1.0;
        }
        if (current <= DEGRADED_TPS) {
            return MIN_RELIABILITY;
        }
        double ratio = (current - DEGRADED_TPS) / (HEALTHY_TPS - DEGRADED_TPS);
        return MIN_RELIABILITY + ratio * (1.0 - MIN_RELIABILITY);
    }

    /**
     * 延迟放宽系数：延迟未知（&lt;0）或正常（≤50ms）时为 1.0，300ms 及以上时为
     * {@value #MAX_LATENCY_FACTOR}，中间线性插值。调用方用它放大阈值（如放宽包到达间隔容忍度）。
     */
    public double latencyFactor(long latencyMs) {
        if (latencyMs < 0) {
            return 1.0;
        }
        if (latencyMs <= NORMAL_LATENCY_MS) {
            return 1.0;
        }
        if (latencyMs >= HIGH_LATENCY_MS) {
            return MAX_LATENCY_FACTOR;
        }
        double ratio = (latencyMs - NORMAL_LATENCY_MS) / (HIGH_LATENCY_MS - NORMAL_LATENCY_MS);
        return 1.0 + ratio * (MAX_LATENCY_FACTOR - 1.0);
    }

    /** 单行摘要，供启动日志与调试输出。 */
    public String describe() {
        return String.format(Locale.ROOT, "TPS≈%.2f 刻长≈%.1fms 可靠性=%.2f",
                tps, effectiveTickMs(), reliabilityFactor());
    }

    /**
     * 包时序阈值放宽系数：在延迟放宽的基础上，低 TPS 再叠一档。
     *
     * <p>用途是把判定门槛往上抬（例如 Timer 阈值 1.005 → 1.005 × 系数），而不是改动
     * 客户端标称发包间隔。延迟未知 / 正常、TPS 健康时返回 1.0，与引入校准器之前完全一致。
     */
    public double thresholdScale(long latencyMs) {
        double scale = latencyFactor(latencyMs);
        if (lowThroughput()) {
            // 低 TPS 下包成批到达，间隔方差远大于正常运行；额外放宽半档
            scale += LOW_TPS_THRESHOLD_BONUS;
        }
        return Math.min(MAX_THRESHOLD_SCALE, scale);
    }

    private int sampleCount() {
        synchronized (tickIntervals) {
            return tickIntervals.size();
        }
    }

    private double averageInterval() {
        long sum = 0;
        int count = 0;
        synchronized (tickIntervals) {
            for (long interval : tickIntervals) {
                sum += interval;
                count++;
            }
        }
        return count == 0 ? -1 : (double) sum / count;
    }
}
