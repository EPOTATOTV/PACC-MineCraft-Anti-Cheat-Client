package com.potatotv.pacc.detection.server.combat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.profile.AimProfile;
import com.potatotv.pacc.detection.profile.RotationSample;

/**
 * 攻击旋转分析（技术文档 §7.3 旋转分析 / §4.1 P-02）。
 *
 * <p>滚动窗口收集攻击方的视角采样，攒够 {@link AimProfile#MIN_SAMPLES} 条后用
 * {@link AimProfile#fromRotations(List)} 提取特征：机器旋转「完美平滑 + 瞬间锁定 + 零过冲」，
 * 人类旋转有噪声、过冲与回调。
 *
 * <p>P-02 追加 ML 伪装对抗：窗口扩大到 {@value #WINDOW_SIZE} 帧，除了基础启发式，还在同一份
 * 画像里计算「平滑方差 + 旋转-CPS 相关性 + 长窗口周期」三项时序特征。ML 转头通常会把
 * 基础启发式压到阈值以下，所以这里单独出一条 {@value #ML_DETECTOR_ID} 证据，交给融合层
 * 作为 ML 伪装置信度分量处理。
 *
 * <p>置信口径：这是启发式评分而非云端 ML 模型输出，不适用
 * {@code ConfidenceScorer.mlGate} 的 0.95 ML 门槛（那是给云端训练模型留的采信门槛，
 * 文档 §8.1）；这里直接以画像似然为置信度，0.6 为出证阈值。
 */
public final class RotationAnalyzer {

    public static final String DETECTOR_ID = "combat-rotation";

    /** ML 伪装证据的检测器标识；融合层据此累加 ML 伪装置信度分量。 */
    public static final String ML_DETECTOR_ID = "combat-rotation-ml";

    /** 出证阈值：机器似然 ≥ 0.6 视为可疑。 */
    public static final double EVIDENCE_THRESHOLD = 0.6;

    /** ML 伪装单独出证的阈值。 */
    public static final double ML_EVIDENCE_THRESHOLD = 0.65;

    /** 滚动窗口容量。长窗口才能观察自相关周期，40 帧太短。 */
    private static final int WINDOW_SIZE = 120;

    private final ConcurrentHashMap<String, Deque<RotationSample>> windows = new ConcurrentHashMap<>();

    /** 逐条记录视角采样。 */
    public void record(String playerId, RotationSample sample) {
        if (sample == null) {
            return;
        }
        Deque<RotationSample> window = windows.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        synchronized (window) {
            if (window.size() >= WINDOW_SIZE) {
                window.pollFirst();
            }
            window.addLast(sample);
        }
    }

    /** 批量记录一个窗口的采样。 */
    public void record(String playerId, List<RotationSample> batch) {
        if (batch == null) {
            return;
        }
        for (RotationSample sample : batch) {
            record(playerId, sample);
        }
    }

    /** 评估当前窗口；样本不足不下结论。 */
    public Optional<Evidence> check(String playerId) {
        Deque<RotationSample> window = windows.get(playerId);
        if (window == null) {
            return Optional.empty();
        }
        List<RotationSample> snapshot;
        synchronized (window) {
            snapshot = List.copyOf(window);
        }
        AimProfile profile = AimProfile.fromRotations(snapshot);
        double likelihood = profile.machineLikelihood();
        double ml = profile.mlDisguiseLikelihood();
        if (likelihood >= EVIDENCE_THRESHOLD) {
            String detail = String.format(Locale.ROOT,
                    "机器旋转似然=%.2f 样本=%d 锁定比=%.2f yawRate方差=%.2f 平滑jerk=%.3f ML伪装=%.2f",
                    likelihood, profile.sampleCount(), profile.snapRatio(), profile.yawRateVariance(),
                    profile.jerkVariance(), ml);
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, likelihood, detail));
        }
        if (ml >= ML_EVIDENCE_THRESHOLD) {
            // 基础启发式被 ML 伪装压下去时，用独立证据把时序异常交给融合层
            double confidence = Math.min(0.95, 0.6 + 0.35 * ml);
            String detail = String.format(Locale.ROOT,
                    "ML 旋转伪装 似然=%.2f 旋转-CPS相关=%.2f 长窗口周期=%.2f 平滑jerk=%.3f 样本=%d",
                    ml, profile.rotationCpsCorrelation(), profile.longWindowAnomaly(),
                    profile.jerkVariance(), profile.sampleCount());
            return Optional.of(Evidence.of(ML_DETECTOR_ID, Layer.BEHAVIOR, confidence, detail));
        }
        return Optional.empty();
    }

    /** 清空玩家窗口（换目标 / 窗口语义重置）。 */
    public void reset(String playerId) {
        windows.remove(playerId);
    }
}
