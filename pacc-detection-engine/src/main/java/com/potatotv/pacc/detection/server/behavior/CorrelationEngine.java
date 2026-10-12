package com.potatotv.pacc.detection.server.behavior;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.potatotv.pacc.detection.fusion.ConfidenceScorer;
import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

/**
 * 跨维度相关性引擎（技术文档 §7.4）。
 *
 * <p>口径：单维度异常只提置信度，多维度同时异常才告警——
 * KillAura = 攻击模式 + 旋转 + 目标切换同时出现。本引擎把同一检测窗口内
 * 不同检测器的证据按 detectorId 分组，≥2 个检测器同时出证即合并：
 * confidence = noisy-OR（{@link ConfidenceScorer#combine(double...)}），
 * detail 列出参与检测器与各自证据数。
 */
public final class CorrelationEngine {

    public static final String DETECTOR_ID = "behavior-correlation";

    /** 触发合并所需的最小不同检测器数。 */
    public static final int MIN_DETECTORS = 2;

    /**
     * 对一个检测窗口的证据做跨维度合并；不足 {@value #MIN_DETECTORS} 个检测器返回 empty。
     */
    public Optional<Evidence> correlate(String playerId, List<Evidence> windowEvidence) {
        if (windowEvidence == null || windowEvidence.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Integer> byDetector = new LinkedHashMap<>();
        for (Evidence evidence : windowEvidence) {
            byDetector.merge(evidence.detectorId(), 1, Integer::sum);
        }
        if (byDetector.size() < MIN_DETECTORS) {
            return Optional.empty();
        }
        double[] confidences = new double[windowEvidence.size()];
        StringBuilder detail = new StringBuilder("跨维度相关 玩家=").append(playerId).append(" 检测器=");
        int index = 0;
        for (Map.Entry<String, Integer> entry : byDetector.entrySet()) {
            detail.append(String.format(Locale.ROOT, "%s(x%d) ", entry.getKey(), entry.getValue()));
            for (Evidence evidence : windowEvidence) {
                if (evidence.detectorId().equals(entry.getKey())) {
                    confidences[index++] = evidence.confidence();
                }
            }
        }
        double combined = ConfidenceScorer.combine(confidences);
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, combined, detail.toString().trim()));
    }
}
