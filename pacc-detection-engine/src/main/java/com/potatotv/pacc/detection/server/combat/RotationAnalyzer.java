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
 * 攻击旋转分析（技术文档 §7.3 旋转分析）。
 *
 * <p>滚动窗口收集攻击方的视角采样，攒够 {@link AimProfile#MIN_SAMPLES} 条后用
 * {@link AimProfile#fromRotations(List)} 提取特征：机器旋转「完美平滑 + 瞬间锁定 + 零过冲」，
 * 人类旋转有噪声、过冲与回调。
 *
 * <p>置信口径：这是启发式评分而非 ML 模型输出，不适用
 * {@code ConfidenceScorer.mlGate} 的 0.95 ML 门槛（那是给云端训练模型留的采信门槛，
 * 文档 §8.1）；这里直接以 machineLikelihood 为置信度，0.6 为出证阈值。
 */
public final class RotationAnalyzer {

    public static final String DETECTOR_ID = "combat-rotation";

    /** 出证阈值：机器似然 ≥ 0.6 视为可疑。 */
    public static final double EVIDENCE_THRESHOLD = 0.6;

    /** 滚动窗口容量。 */
    private static final int WINDOW_SIZE = 40;

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
        if (likelihood < EVIDENCE_THRESHOLD) {
            return Optional.empty();
        }
        String detail = String.format(Locale.ROOT,
                "机器旋转似然=%.2f 样本=%d 锁定比=%.2f yawRate方差=%.2f",
                likelihood, profile.sampleCount(), profile.snapRatio(), profile.yawRateVariance());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, likelihood, detail));
    }

    /** 清空玩家窗口（换目标 / 窗口语义重置）。 */
    public void reset(String playerId) {
        windows.remove(playerId);
    }
}
