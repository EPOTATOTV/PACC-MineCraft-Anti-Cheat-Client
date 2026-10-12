package com.potatotv.pacc.detection.profile;

import java.util.List;

/**
 * 旋转特征画像：人类旋转有噪声、不精确、非匀速（过冲后回调）；
 * 机器旋转呈现「完美平滑」或「瞬间锁定」（技术文档 §7.3）。
 *
 * <p>三个特征维度：
 * <ul>
 *   <li>角速度方差：机器匀速转动时方差趋零</li>
 *   <li>锁定比：单次采样内大角度突跳（snap）占攻击采样的比例</li>
 *   <li>过冲率：人类瞄准有方向反复（过冲回调），机器单调逼近时接近零</li>
 * </ul>
 */
public record AimProfile(
        int sampleCount,
        double meanYawRateDegPerSec,
        double yawRateVariance,
        double snapRatio,
        double overshootRatio,
        double machineLikelihood) {

    /** 单次采样 yaw 突变超过该角度视为 snap（瞬间锁定）。 */
    public static final double SNAP_THRESHOLD_DEG = 40.0;

    public static AimProfile empty() {
        return new AimProfile(0, 0, 0, 0, 0, 0);
    }

    /** 样本不足不下结论。 */
    public static final int MIN_SAMPLES = 10;

    public static AimProfile fromRotations(List<RotationSample> rotations) {
        if (rotations == null || rotations.size() < MIN_SAMPLES) {
            return empty();
        }
        double[] rates = new double[rotations.size() - 1];
        int snaps = 0;
        int reversals = 0;
        int attackingSamples = 0;
        double lastDeltaSign = 0;
        for (int i = 0; i < rates.length; i++) {
            RotationSample a = rotations.get(i);
            RotationSample b = rotations.get(i + 1);
            rates[i] = RotationSample.yawRate(a, b);
            double delta = RotationSample.normalizeDelta(b.yawDeg() - a.yawDeg());
            if (Math.abs(delta) >= SNAP_THRESHOLD_DEG) {
                snaps++;
            }
            if (b.attacking()) {
                attackingSamples++;
            }
            double sign = Math.signum(delta);
            if (sign != 0 && lastDeltaSign != 0 && sign != lastDeltaSign) {
                reversals++;
            }
            if (sign != 0) {
                lastDeltaSign = sign;
            }
        }
        double mean = ClickProfile.mean(rates);
        double variance = ClickProfile.variance(rates, mean);
        int attackPairs = Math.max(1, attackingSamples);
        double snapRatio = (double) snaps / attackPairs;
        // 过冲率：人类在瞄准目标时来回修正；单调逼近（几乎无反转）在长序列里是机器特征
        double overshootRatio = rates.length < 20 ? 0.0 : (double) reversals / rates.length;
        double machine = scoreMachine(mean, variance, snapRatio, overshootRatio);
        return new AimProfile(rotations.size(), mean, variance, snapRatio, overshootRatio, machine);
    }

    /**
     * 机器 likelihood 启发式评分：
     * 低方差匀速（完美平滑）+ 高锁定比（瞬间锁定）+ 零过冲（无人类噪声）。
     */
    private static double scoreMachine(double meanRate, double variance, double snapRatio, double overshootRatio) {
        double smoothScore = variance < 1.0 ? 1.0 : Math.max(0.0, 1.0 - variance / 100.0);
        double snapScore = Math.min(1.0, snapRatio * 2.0);
        double noNoiseScore = overshootRatio < 0.02 ? 1.0 : Math.max(0.0, 1.0 - overshootRatio * 5.0);
        boolean hasMotion = meanRate > 5.0;
        if (!hasMotion) {
            return 0.0;
        }
        return Math.min(1.0, 0.35 * smoothScore + 0.40 * snapScore + 0.25 * noNoiseScore);
    }
}
