package com.potatotv.pacc.detection.fusion;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfidenceScorer} 评分工具测试：归一化、ML 置信门、noisy-OR 组合与默认权重表。
 */
class ConfidenceScorerTest {

    private static final double DELTA = 1e-9;

    @Test
    void normalizeClampsToUnitRange() {
        assertEquals(0.62, ConfidenceScorer.normalize(62), DELTA);
        assertEquals(0.0, ConfidenceScorer.normalize(-5), DELTA);
        assertEquals(1.0, ConfidenceScorer.normalize(150), DELTA);
        assertEquals(0.0, ConfidenceScorer.normalize(0), DELTA);
        assertEquals(1.0, ConfidenceScorer.normalize(100), DELTA);
    }

    @Test
    void mlGateRejectsBelowDefaultThreshold() {
        assertEquals(0.0, ConfidenceScorer.mlGate(0.94), DELTA);
        assertEquals(0.95, ConfidenceScorer.mlGate(0.95), DELTA);
        assertEquals(0.99, ConfidenceScorer.mlGate(0.99), DELTA);
        assertEquals(0.95, ConfidenceScorer.DEFAULT_ML_GATE, DELTA);
    }

    @Test
    void mlGateHonoursCustomThreshold() {
        assertEquals(0.0, ConfidenceScorer.mlGate(0.7, 0.8), DELTA);
        assertEquals(0.8, ConfidenceScorer.mlGate(0.8, 0.8), DELTA);
        assertEquals(0.9, ConfidenceScorer.mlGate(0.9, 0.8), DELTA);
    }

    @Test
    void combineUsesNoisyOr() {
        assertEquals(0.75, ConfidenceScorer.combine(0.5, 0.5), DELTA);
        assertEquals(0.44, ConfidenceScorer.combine(0.2, 0.3), DELTA);
        assertEquals(0.0, ConfidenceScorer.combine(), "无证据时应为 0");
        assertEquals(1.0, ConfidenceScorer.combine(1.0, 0.5), DELTA);
    }

    @Test
    void combineIsMonotonicInExtraEvidence() {
        double base = ConfidenceScorer.combine(0.4);
        double withMore = ConfidenceScorer.combine(0.4, 0.4);
        double withEvenMore = ConfidenceScorer.combine(0.4, 0.4, 0.4);
        assertTrue(base < withMore && withMore < withEvenMore, "追加独立证据应单调抬升组合置信度");
    }

    @Test
    void defaultLayerWeightsMatchLayerDefaults() {
        Map<Layer, Double> weights = ConfidenceScorer.defaultLayerWeights();
        for (Layer layer : Layer.values()) {
            assertEquals(layer.defaultWeight(), weights.get(layer), DELTA, "层 " + layer + " 权重不符");
        }
    }
}
