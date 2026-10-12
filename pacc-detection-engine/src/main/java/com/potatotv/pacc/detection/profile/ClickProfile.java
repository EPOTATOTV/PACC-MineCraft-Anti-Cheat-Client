package com.potatotv.pacc.detection.profile;

import java.util.List;

/**
 * 点击分布画像：人类点击高熵、间隔方差大；机械点击方差趋零、间隔呈 GCD 倍数（技术文档 §6.4）。
 *
 * <p>判定口径（文档伪代码原样落地）：
 * <ul>
 *   <li>{@code robotic = intervalVariance < 0.5 && entropy < 3.0}（方差以秒计）</li>
 *   <li>{@code gcdPattern = gcd 大于 20ms 的占比 > 0.8}</li>
 * </ul>
 */
public record ClickProfile(
        int clickCount,
        double meanIntervalMs,
        double intervalVarianceSeconds,
        double entropyBits,
        double gcdPatternRatio,
        boolean robotic,
        boolean gcdPattern) {

    /** 间隔样本不足时不做结论。 */
    public static final int MIN_CLICKS = 8;

    public static ClickProfile empty() {
        return new ClickProfile(0, 0, 0, 0, 0, false, false);
    }

    /**
     * 从点击时间戳序列构造画像；相邻时间戳取差得到间隔序列。
     * 样本少于 {@link #MIN_CLICKS} 返回空画像，避免单次误判（文档 §5.1：采集 30 秒窗口）。
     */
    public static ClickProfile fromClickTimestamps(List<Long> clickTimestamps) {
        if (clickTimestamps == null || clickTimestamps.size() < MIN_CLICKS) {
            return empty();
        }
        double[] intervals = new double[clickTimestamps.size() - 1];
        for (int i = 0; i < intervals.length; i++) {
            long diff = clickTimestamps.get(i + 1) - clickTimestamps.get(i);
            intervals[i] = Math.max(1.0, diff);
        }
        double mean = mean(intervals);
        double varianceSeconds = variance(intervals, mean) / 1_000_000.0;
        double entropy = entropyBits(intervals);
        double gcdRatio = gcdPatternRatio(intervals);
        boolean robotic = varianceSeconds < 0.5 && entropy < 3.0;
        boolean gcdPattern = gcdRatio > 0.8;
        return new ClickProfile(
                clickTimestamps.size(), mean, varianceSeconds, entropy, gcdRatio, robotic, gcdPattern);
    }

    /** 机械程度 0-1：方差趋零、熵低、GCD 模式明显三项加权。 */
    public double machineLikelihood() {
        if (clickCount < MIN_CLICKS) {
            return 0.0;
        }
        double varScore = intervalVarianceSeconds < 0.5 ? 1.0 : Math.max(0.0, 1.0 - intervalVarianceSeconds / 2.0);
        double entropyScore = entropyBits < 3.0 ? 1.0 - entropyBits / 3.0 : 0.0;
        return Math.min(1.0, 0.4 * varScore + 0.3 * entropyScore + 0.3 * (gcdPattern ? 1.0 : gcdPatternRatio));
    }

    static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    static double variance(double[] values, double mean) {
        double sum = 0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return sum / values.length;
    }

    /**
     * 香农熵（bit）：把间隔按 50ms 桶分箱统计分布。
     * 人类间隔分布宽、熵高；机械工具固定间隔则熵趋零。
     */
    static double entropyBits(double[] intervals) {
        if (intervals.length == 0) {
            return 0.0;
        }
        java.util.Map<Long, Integer> buckets = new java.util.HashMap<>();
        for (double v : intervals) {
            buckets.merge((long) (v / 50.0), 1, Integer::sum);
        }
        double entropy = 0.0;
        for (int count : buckets.values()) {
            double p = (double) count / intervals.length;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /** 计算所有间隔两两 GCD 后，统计大于 20ms 的 GCD 占比（机械工具间隔常为固定毫秒倍数）。 */
    static double gcdPatternRatio(double[] intervals) {
        if (intervals.length < 2) {
            return 0.0;
        }
        int hits = 0;
        int pairs = 0;
        for (int i = 0; i < intervals.length; i++) {
            for (int j = i + 1; j < intervals.length; j++) {
                long g = gcd(Math.round(intervals[i]), Math.round(intervals[j]));
                pairs++;
                if (g > 20) {
                    hits++;
                }
            }
        }
        return pairs == 0 ? 0.0 : (double) hits / pairs;
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
