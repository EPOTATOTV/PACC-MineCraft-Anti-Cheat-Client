package com.potatotv.pacc.detection.fusion;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 六层证据融合（技术文档 §4.1 / §8.1）。
 *
 * <p>先在层内用 noisy-OR 聚合多条证据（多条独立弱证据相互印证会推高层分），
 * 再按层权重加权求和得到总分。公式：
 * <pre>
 *   layerScore(L) = 1 - Π(1 - confidence_i)     （层内 noisy-OR）
 *   score         = Σ layerScore(L) * weight(L)  （跨层加权，封顶 1.0）
 * </pre>
 */
public final class MultiLayerFusion {

    /** 每层的聚合分。 */
    public record LayerScores(Map<Layer, Double> scores) {
        public double get(Layer layer) {
            return scores.getOrDefault(layer, 0.0);
        }
    }

    /** 计算每层聚合分。 */
    public LayerScores aggregate(List<Evidence> evidence) {
        Map<Layer, Double> miss = new EnumMap<>(Layer.class);
        for (Evidence ev : evidence) {
            miss.merge(ev.layer(), ev.confidence(), (a, b) -> 1.0 - (1.0 - a) * (1.0 - b));
        }
        Map<Layer, Double> scores = new EnumMap<>(Layer.class);
        for (Layer layer : Layer.values()) {
            scores.put(layer, clamp(miss.getOrDefault(layer, 0.0)));
        }
        return new LayerScores(scores);
    }

    /** 按给定权重把各层聚合分加权为总分（0-1，封顶 1.0）。 */
    public double weightedSum(LayerScores scores, Map<Layer, Double> weights) {
        double total = 0.0;
        for (Layer layer : Layer.values()) {
            total += scores.get(layer) * weights.getOrDefault(layer, layer.defaultWeight());
        }
        return clamp(total);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
