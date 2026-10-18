package com.potatotv.pacc.detection.server.behavior;

import com.potatotv.pacc.detection.fusion.ConfidenceScorer;
import com.potatotv.pacc.detection.fusion.Evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightProvenanceTest {

    @Test
    void heuristicDefaultIsDegradedAndCarriesSeed() {
        WeightProvenance provenance = WeightProvenance.heuristicDefault(20260101L);
        assertTrue(provenance.degraded());
        assertTrue(provenance.tag().contains("seed:20260101"));
        assertTrue(provenance.trainingRecord().isEmpty());
    }

    @Test
    void trainedRequiresTrainingRecord() {
        assertThrows(IllegalArgumentException.class, () -> WeightProvenance.trained("v1", " "));
        WeightProvenance provenance = WeightProvenance.trained("v1", "replay-2026-10");
        assertFalse(provenance.degraded());
        assertTrue(provenance.tag().contains("record=replay-2026-10"));
    }

    @Test
    void unspecifiedIsNotTreatedAsDegraded() {
        assertFalse(WeightProvenance.unspecified().degraded());
    }

    @Test
    void degradedWeightsNeverEmitEvidence() {
        // 用大输出偏置把 p 顶到 0.99，验证降级权重仍然拒绝出证
        LstmWeights weights = highConfidenceWeights(3, 4);
        MLDetector detector = new MLDetector(3, 4, weights, WeightProvenance.heuristicDefault(7L));
        assertTrue(detector.degraded());
        assertTrue(detector.classify("p", sequence()).isEmpty());
        assertTrue(detector.statusLine().contains("降级"));
    }

    @Test
    void trainedWeightsEmitEvidenceWithProvenanceTag() {
        LstmWeights weights = highConfidenceWeights(3, 4);
        MLDetector detector = new MLDetector(3, 4, weights,
                WeightProvenance.trained("v1", "replay-2026-10"));
        assertFalse(detector.degraded());
        Evidence evidence = detector.classify("p", sequence()).orElseThrow();
        assertTrue(evidence.confidence() >= ConfidenceScorer.DEFAULT_ML_GATE);
        assertTrue(evidence.detail().contains("record=replay-2026-10"));
    }

    /** 全零权重 + 大输出偏置：p = sigmoid(10) ≈ 0.99995。 */
    private static LstmWeights highConfidenceWeights(int inputSize, int hiddenSize) {
        int length = 4 * (hiddenSize * inputSize + hiddenSize * hiddenSize + hiddenSize) + hiddenSize + 1;
        double[] flat = new double[length];
        flat[length - 1] = 10.0;
        return LstmWeights.fromFlat(flat, inputSize, hiddenSize);
    }

    private static List<double[]> sequence() {
        return List.of(new double[] {0.2, 0.5, 0.9}, new double[] {0.7, 0.1, 0.3});
    }

    @Test
    void tagIsStable() {
        assertEquals("weights=heuristic_default v=seed:1", WeightProvenance.heuristicDefault(1L).tag());
    }
}
