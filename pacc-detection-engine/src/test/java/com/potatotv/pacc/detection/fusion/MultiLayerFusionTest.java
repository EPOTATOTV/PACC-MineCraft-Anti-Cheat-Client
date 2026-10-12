package com.potatotv.pacc.detection.fusion;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MultiLayerFusion} 层内 noisy-OR 聚合与跨层加权测试。
 */
class MultiLayerFusionTest {

    private static final double DELTA = 1e-9;

    private final MultiLayerFusion fusion = new MultiLayerFusion();

    @Test
    void singleEvidenceEqualsItsConfidence() {
        MultiLayerFusion.LayerScores scores = fusion.aggregate(List.of(memory(0.6)));
        assertEquals(0.6, scores.get(Layer.MEMORY), DELTA);
        assertEquals(0.0, scores.get(Layer.MODULE), DELTA, "未出现证据的层为 0");
    }

    @Test
    void aggregatingAllLayersYieldsEveryLayerZeroByDefault() {
        MultiLayerFusion.LayerScores scores = fusion.aggregate(List.of());
        for (Layer layer : Layer.values()) {
            assertEquals(0.0, scores.get(layer), DELTA);
        }
    }

    @Test
    void multipleEvidenceInSameLayerUseNoisyOr() {
        MultiLayerFusion.LayerScores scores = fusion.aggregate(List.of(memory(0.5), memory(0.5)));
        assertEquals(0.75, scores.get(Layer.MEMORY), DELTA, "0.5 与 0.5 的 noisy-OR 为 0.75");
    }

    @Test
    void differentLayersAggregateIndependently() {
        MultiLayerFusion.LayerScores scores = fusion.aggregate(List.of(memory(0.5), module(0.5)));
        assertEquals(0.5, scores.get(Layer.MEMORY), DELTA);
        assertEquals(0.5, scores.get(Layer.MODULE), DELTA);
    }

    @Test
    void weightedSumAppliesDefaultLayerWeights() {
        Map<Layer, Double> weights = ConfidenceScorer.defaultLayerWeights();
        MultiLayerFusion.LayerScores memory = fusion.aggregate(List.of(memory(1.0)));
        assertEquals(Layer.MEMORY.defaultWeight(), fusion.weightedSum(memory, weights), DELTA);
        MultiLayerFusion.LayerScores network = fusion.aggregate(List.of(
                Evidence.of("proxy-detect", Layer.NETWORK, 1.0, "代理")));
        assertEquals(Layer.NETWORK.defaultWeight(), fusion.weightedSum(network, weights), DELTA);
    }

    @Test
    void weightedSumIsCappedAtOne() {
        MultiLayerFusion.LayerScores scores = fusion.aggregate(List.of(
                memory(1.0), module(1.0), Evidence.of("process-scan", Layer.PROCESS, 1.0, "进程")));
        assertEquals(1.0, fusion.weightedSum(scores, ConfidenceScorer.defaultLayerWeights()), DELTA);
    }

    @Test
    void addingEvidenceNeverLowersWeightedSum() {
        Map<Layer, Double> weights = ConfidenceScorer.defaultLayerWeights();
        double one = fusion.weightedSum(fusion.aggregate(List.of(memory(0.5))), weights);
        double two = fusion.weightedSum(fusion.aggregate(List.of(memory(0.5), module(0.5))), weights);
        double three = fusion.weightedSum(
                fusion.aggregate(List.of(memory(0.5), module(0.5), Evidence.of("process-scan", Layer.PROCESS, 0.5, "进程"))),
                weights);
        assertTrue(one < two && two < three, "追加独立层证据应单调抬升总分");
    }

    @Test
    void customWeightsScaleContribution() {
        Map<Layer, Double> weights = new EnumMap<>(Layer.class);
        weights.put(Layer.MEMORY, 0.25);
        MultiLayerFusion.LayerScores scores = fusion.aggregate(List.of(memory(0.8)));
        assertEquals(0.2, fusion.weightedSum(scores, weights), DELTA);
    }

    private static Evidence memory(double confidence) {
        return Evidence.of("memory-scan", Layer.MEMORY, confidence, "内存篡改");
    }

    private static Evidence module(double confidence) {
        return Evidence.of("module-scan", Layer.MODULE, confidence, "注入模块");
    }
}
