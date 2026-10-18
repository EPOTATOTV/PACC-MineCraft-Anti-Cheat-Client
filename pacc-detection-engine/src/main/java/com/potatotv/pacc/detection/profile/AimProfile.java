package com.potatotv.pacc.detection.profile;

import java.util.List;

/**
 * 旋转特征画像：人类旋转有噪声、不精确、非匀速（过冲后回调）；
 * 机器旋转呈现「完美平滑」或「瞬间锁定」（技术文档 §7.3）。
 *
 * <p>三个基础特征维度：
 * <ul>
 *   <li>角速度方差：机器匀速转动时方差趋零</li>
 *   <li>锁定比：单次采样内大角度突跳（snap）占攻击采样的比例</li>
 *   <li>过冲率：人类瞄准有方向反复（过冲回调），机器单调逼近时接近零</li>
 * </ul>
 *
 * <p>P-02 追加时序模型对抗（针对 NanokaLite 类「ML 转头」）：
 * <ul>
 *   <li>平滑方差 {@code jerkVariance}：角速度二阶差分的离散度。ML 伪装会刻意注入抖动，
 *       但抖动通常叠加在一条低 jerk 的平滑轨迹上，二阶差分比一阶方差更能拆穿</li>
 *   <li>旋转-CPS 相关性 {@code rotationCpsCorrelation}：角速度与攻击帧的点双列相关。
 *       人类瞄准在攻击前后仍有修正；ML 转头往往把「转到目标」和「出手」绑成同一拍</li>
 *   <li>长窗口周期异常 {@code longWindowAnomaly}：角速度序列的自相关峰。模型生成的转头
 *       在长窗口里会出现重复节拍，人类不会</li>
 * </ul>
 *
 * <p>{@code machineLikelihood} 是基础启发式与 ML 伪装似然的合成值。置信口径仍是启发式评分，
 * 不走 {@code ConfidenceScorer.mlGate} 的 0.95 云端模型门槛。
 */
public record AimProfile(
        int sampleCount,
        double meanYawRateDegPerSec,
        double yawRateVariance,
        double snapRatio,
        double overshootRatio,
        double machineLikelihood,
        double jerkVariance,
        double rotationCpsCorrelation,
        double longWindowAnomaly,
        double mlDisguiseLikelihood) {

    /** 单次采样 yaw 突变超过该角度视为 snap（瞬间锁定）。 */
    public static final double SNAP_THRESHOLD_DEG = 40.0;

    /** 长窗口自相关分析的最小样本数。 */
    public static final int LONG_WINDOW_MIN_SAMPLES = 60;

    /** 兼容旧构造：只给基础特征时，时序特征归零。 */
    public AimProfile(int sampleCount, double meanYawRateDegPerSec, double yawRateVariance,
                      double snapRatio, double overshootRatio, double machineLikelihood) {
        this(sampleCount, meanYawRateDegPerSec, yawRateVariance, snapRatio, overshootRatio,
                machineLikelihood, 0.0, 0.0, 0.0, 0.0);
    }

    public static AimProfile empty() {
        return new AimProfile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 样本不足不下结论。 */
    public static final int MIN_SAMPLES = 10;

    public static AimProfile fromRotations(List<RotationSample> rotations) {
        if (rotations == null || rotations.size() < MIN_SAMPLES) {
            return empty();
        }
        double[] rates = new double[rotations.size() - 1];
        boolean[] attacking = new boolean[rates.length];
        int snaps = 0;
        int reversals = 0;
        double lastDeltaSign = 0;
        for (int i = 0; i < rates.length; i++) {
            RotationSample a = rotations.get(i);
            RotationSample b = rotations.get(i + 1);
            rates[i] = RotationSample.yawRate(a, b);
            attacking[i] = b.attacking();
            double delta = RotationSample.normalizeDelta(b.yawDeg() - a.yawDeg());
            if (Math.abs(delta) >= SNAP_THRESHOLD_DEG) {
                snaps++;
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
        int attackPairs = Math.max(1, countTrue(attacking));
        double snapRatio = (double) snaps / attackPairs;
        // 过冲率：人类在瞄准目标时来回修正；单调逼近（几乎无反转）在长序列里是机器特征
        double overshootRatio = rates.length < 20 ? 0.0 : (double) reversals / rates.length;
        boolean hasMotion = mean > 5.0;
        double heuristic = hasMotion ? scoreMachine(mean, variance, snapRatio, overshootRatio) : 0.0;

        double jerk = jerkVariance(rates, mean);
        double correlation = Math.abs(rotationCpsCorrelation(rates, attacking));
        double periodicity = longWindowAnomaly(rates);
        double mlDisguise = hasMotion
                ? scoreMlDisguise(jerk, correlation, periodicity)
                : 0.0;
        // ML 分量不覆盖基础启发式，只在基础分之上补足；避免把正常玩家的一次抖动放大成告警
        double machine = Math.min(1.0, Math.max(heuristic, 0.55 * heuristic + 0.45 * mlDisguise));
        return new AimProfile(rotations.size(), mean, variance, snapRatio, overshootRatio, machine,
                jerk, correlation, periodicity, mlDisguise);
    }

    private static int countTrue(boolean[] flags) {
        int n = 0;
        for (boolean flag : flags) {
            if (flag) {
                n++;
            }
        }
        return n;
    }

    /**
     * 机器 likelihood 启发式评分：
     * 低方差匀速（完美平滑）+ 高锁定比（瞬间锁定）+ 零过冲（无人类噪声）。
     */
    private static double scoreMachine(double meanRate, double variance, double snapRatio, double overshootRatio) {
        double smoothScore = variance < 1.0 ? 1.0 : Math.max(0.0, 1.0 - variance / 100.0);
        double snapScore = Math.min(1.0, snapRatio * 2.0);
        double noNoiseScore = overshootRatio < 0.02 ? 1.0 : Math.max(0.0, 1.0 - overshootRatio * 5.0);
        if (meanRate <= 5.0) {
            return 0.0;
        }
        return Math.min(1.0, 0.35 * smoothScore + 0.40 * snapScore + 0.25 * noNoiseScore);
    }

    /**
     * ML 伪装似然：平滑轨迹上的低 jerk、攻击与旋转强相关、长窗口重复节拍，三者加权。
     * 单项高不构成结论，必须组合成立——这是压误报的主要手段。
     */
    private static double scoreMlDisguise(double jerkVariance, double absCorrelation, double periodicity) {
        double smooth = 1.0 / (1.0 + Math.max(0.0, jerkVariance));
        double corrScore = absCorrelation <= 0.45 ? 0.0 : (absCorrelation - 0.45) / 0.55;
        return Math.min(1.0,
                0.35 * smooth + 0.40 * clamp(corrScore) + 0.25 * clamp(periodicity));
    }

    /**
     * 角速度二阶差分的平均绝对值，再按平均角速度归一化。完美匀速为 0；
     * 人类抖动通常高于 0.15，ML 伪装的「平滑 + 细抖」落在 0.02-0.10。
     */
    private static double jerkVariance(double[] rates, double meanRate) {
        if (rates.length < 3) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 2; i < rates.length; i++) {
            sum += Math.abs(rates[i] - 2 * rates[i - 1] + rates[i - 2]);
        }
        double meanJerk = sum / (rates.length - 2);
        return meanJerk / (Math.abs(meanRate) + 1.0);
    }

    /**
     * 点双列相关：角速度（连续）与是否处于攻击帧（二值）。任一序列方差为 0 时返回 0。
     */
    private static double rotationCpsCorrelation(double[] rates, boolean[] attacking) {
        int n = rates.length;
        if (n < MIN_SAMPLES) {
            return 0.0;
        }
        double meanRate = ClickProfile.mean(rates);
        double rateVar = ClickProfile.variance(rates, meanRate);
        int trueCount = countTrue(attacking);
        double p = (double) trueCount / n;
        if (rateVar <= 1e-9 || p <= 0.0 || p >= 1.0) {
            return 0.0;
        }
        double covariance = 0.0;
        for (int i = 0; i < n; i++) {
            covariance += (rates[i] - meanRate) * ((attacking[i] ? 1.0 : 0.0) - p);
        }
        covariance /= n;
        double flagStd = Math.sqrt(p * (1.0 - p));
        return covariance / (Math.sqrt(rateVar) * flagStd);
    }

    /**
     * 长窗口自相关峰：在 5-20 帧滞后范围内取最大正相关，映射到 [0,1]。
     * 样本不足或序列近似常数时返回 0。
     */
    private static double longWindowAnomaly(double[] rates) {
        if (rates.length < LONG_WINDOW_MIN_SAMPLES) {
            return 0.0;
        }
        double mean = ClickProfile.mean(rates);
        double variance = ClickProfile.variance(rates, mean);
        if (variance <= 1e-9) {
            return 0.0;
        }
        double best = 0.0;
        int maxLag = Math.min(20, rates.length / 3);
        for (int lag = 5; lag <= maxLag; lag++) {
            double sum = 0.0;
            for (int i = lag; i < rates.length; i++) {
                sum += (rates[i] - mean) * (rates[i - lag] - mean);
            }
            double corr = sum / ((rates.length - lag) * variance);
            if (corr > best) {
                best = corr;
            }
        }
        return clamp((best - 0.5) / 0.5);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
