package com.potatotv.pacc.detection.fusion;

import java.util.EnumMap;
import java.util.Map;

/**
 * 置信度评分工具（技术文档 §7.4 / §8.1）。
 *
 * <p>统一处理分数归一化、noisy-OR 组合与 ML 置信门槛：
 * 机器学习结论只有达到置信门槛（默认 95%）才允许作为证据上送融合。
 */
public final class ConfidenceScorer {

    /** 默认 ML 告警置信门槛：95%。 */
    public static final double DEFAULT_ML_GATE = 0.95;

    private ConfidenceScorer() {
    }

    /** 0-100 原始分归一化为 0-1。 */
    public static double normalize(double score0to100) {
        return Math.max(0.0, Math.min(1.0, score0to100 / 100.0));
    }

    /**
     * ML 置信门槛：低于门槛返回 0（不出证据），达到门槛原样返回。
     * 对标 arxiv 2512.21377 的 95% 置信度告警策略。
     */
    public static double mlGate(double confidence, double threshold) {
        return confidence >= threshold ? confidence : 0.0;
    }

    /** 默认门槛的 ML 置信门。 */
    public static double mlGate(double confidence) {
        return mlGate(confidence, DEFAULT_ML_GATE);
    }

    /** 多条独立证据的 noisy-OR 组合。 */
    public static double combine(double... confidences) {
        double miss = 1.0;
        for (double c : confidences) {
            miss *= (1.0 - clamp(c));
        }
        return clamp(1.0 - miss);
    }

    /** 层权重表工厂：未显式配置的层取默认权重。 */
    public static Map<Layer, Double> defaultLayerWeights() {
        Map<Layer, Double> weights = new EnumMap<>(Layer.class);
        for (Layer layer : Layer.values()) {
            weights.put(layer, layer.defaultWeight());
        }
        return weights;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
