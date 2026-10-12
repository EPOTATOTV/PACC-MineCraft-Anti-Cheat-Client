package com.potatotv.pacc.detection.profile;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 移动习惯基线：滚动维护水平速度样本的均值与标准差，
 * 偏离基线 3σ 触发复查（技术文档 §7.4 统计离群）。
 */
public final class MovementProfile {

    /** 滚动样本容量。 */
    public static final int WINDOW_SIZE = 512;

    /** 偏离基线的 σ 阈值。 */
    public static final double SIGMA_THRESHOLD = 3.0;

    private final Deque<Double> speeds = new ArrayDeque<>();
    private double sum;
    private double sumSq;

    /** 记录一次水平速度采样（格/tick）。 */
    public synchronized void addSpeed(double horizontalSpeed) {
        if (speeds.size() >= WINDOW_SIZE) {
            double old = speeds.pollFirst();
            sum -= old;
            sumSq -= old * old;
        }
        speeds.addLast(horizontalSpeed);
        sum += horizontalSpeed;
        sumSq += horizontalSpeed * horizontalSpeed;
    }

    public synchronized int sampleCount() {
        return speeds.size();
    }

    public synchronized double mean() {
        return speeds.isEmpty() ? 0.0 : sum / speeds.size();
    }

    public synchronized double std() {
        int n = speeds.size();
        if (n < 2) {
            return 0.0;
        }
        double variance = Math.max(0.0, sumSq / n - mean() * mean());
        return Math.sqrt(variance);
    }

    /**
     * 当前速度相对基线的 z 分数；基线未成型（样本 &lt; 32）返回 0，避免冷启动误报。
     */
    public synchronized double zScore(double speed) {
        if (speeds.size() < 32) {
            return 0.0;
        }
        double std = std();
        if (std < 1e-6) {
            return speed > mean() ? SIGMA_THRESHOLD : 0.0;
        }
        return (speed - mean()) / std;
    }

    /** 是否超出基线 3σ。 */
    public synchronized boolean outlier(double speed) {
        return zScore(speed) > SIGMA_THRESHOLD;
    }
}
