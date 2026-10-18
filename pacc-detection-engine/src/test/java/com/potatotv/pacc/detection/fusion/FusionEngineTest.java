package com.potatotv.pacc.detection.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FusionEngine} 融合判定测试：分档阈值、环境降权与豁免过滤。
 */
class FusionEngineTest {

    private static final double DELTA = 1e-9;

    @Test
    void emptyBatchIsClean() {
        FusionEngine engine = new FusionEngine();
        FusionEngine.FusionResult result = engine.evaluateDetailed(EvidenceBatch.empty("p-empty"));
        assertEquals(0.0, result.score(), DELTA);
        assertEquals(Verdict.CLEAN, result.verdict());
        assertEquals(Verdict.CLEAN, engine.evaluate(EvidenceBatch.empty("p-empty")));
    }

    @Test
    void verdictBoundariesAreExclusive() {
        FusionEngine engine = new FusionEngine();
        // L3 权重 1.0，证据置信度直接等于总分，方便钉住边界
        assertEquals(Verdict.CLEAN, engine.evaluate(batch(memory(0.70))), "0.70 不大于 0.7，应判 CLEAN");
        assertEquals(Verdict.SUSPECT, engine.evaluate(batch(memory(0.90))), "0.90 不大于 0.9，应判 SUSPECT");
        assertEquals(Verdict.CONFIRMED, engine.evaluate(batch(memory(0.91))), "0.91 大于 0.9，应判 CONFIRMED");
    }

    @Test
    void suspectAndConfirmedTiers() {
        FusionEngine engine = new FusionEngine();
        assertEquals(Verdict.SUSPECT, engine.evaluate(batch(memory(0.75))));
        assertEquals(Verdict.CONFIRMED, engine.evaluate(batch(memory(0.95))));
    }

    @Test
    void higherConfidenceNeverLowersScore() {
        FusionEngine engine = new FusionEngine();
        double low = engine.evaluateDetailed(batch(memory(0.40))).score();
        double mid = engine.evaluateDetailed(batch(memory(0.60))).score();
        double high = engine.evaluateDetailed(batch(memory(0.80))).score();
        assertTrue(low < mid && mid < high, "同层证据置信度越高分数应单调不减");
    }

    @Test
    void networkAnomalySuppressesScoreByFactor() {
        FusionEngine engine = new FusionEngine();
        FusionEngine.FusionResult normal = engine.evaluateDetailed(batch(memory(0.95)));
        assertEquals(Verdict.CONFIRMED, normal.verdict());
        assertFalse(normal.networkSuppressed());

        EvidenceBatch anomalous = new EvidenceBatch(
                "p-net", List.of(memory(0.95)), true, false, Map.of());
        FusionEngine.FusionResult suppressed = engine.evaluateDetailed(anomalous);
        assertEquals(0.95 * FalsePositiveGuard.NETWORK_ANOMALY_FACTOR, suppressed.score(), DELTA);
        assertTrue(suppressed.networkSuppressed());
        assertEquals(Verdict.CLEAN, suppressed.verdict());
    }

    @Test
    void pluginInterferenceSuppressesScoreByFactor() {
        FusionEngine engine = new FusionEngine();
        EvidenceBatch interfered = new EvidenceBatch(
                "p-plugin", List.of(memory(0.95)), false, true, Map.of());
        FusionEngine.FusionResult result = engine.evaluateDetailed(interfered);
        assertEquals(0.95 * FalsePositiveGuard.PLUGIN_INTERFERENCE_FACTOR, result.score(), DELTA);
        assertTrue(result.pluginSuppressed());
        assertEquals(Verdict.CLEAN, result.verdict());
    }

    @Test
    void bothEnvironmentFlagsMultiplySuppression() {
        FusionEngine engine = new FusionEngine();
        EvidenceBatch both = new EvidenceBatch("p-both", List.of(memory(1.0)), true, true, Map.of());
        FusionEngine.FusionResult result = engine.evaluateDetailed(both);
        assertEquals(FalsePositiveGuard.NETWORK_ANOMALY_FACTOR * FalsePositiveGuard.PLUGIN_INTERFERENCE_FACTOR,
                result.score(), DELTA);
    }

    @Test
    void creativeContextDropsPredictionEvidence() {
        FusionEngine engine = new FusionEngine();
        Evidence prediction = Evidence.of("prediction-fly", Layer.BEHAVIOR, 0.95, "悬空上升");
        // 无豁免上下文：L6 权重 0.75 => 0.7125 判 SUSPECT
        FusionEngine.FusionResult without = engine.evaluateDetailed(
                new EvidenceBatch("p-creative", List.of(prediction), false, false, Map.of()));
        assertEquals(0.95 * Layer.BEHAVIOR.defaultWeight(), without.score(), DELTA);
        assertEquals(Verdict.SUSPECT, without.verdict());

        // 创造模式豁免：物理预测类证据被整体丢弃
        FusionEngine.FusionResult with = engine.evaluateDetailed(
                new EvidenceBatch("p-creative", List.of(prediction), false, false, Map.of("creative", "true")));
        assertEquals(0.0, with.score(), DELTA);
        assertEquals(Verdict.CLEAN, with.verdict());
    }

    @Test
    void lagSpikeDropsMovementEvidence() {
        FusionEngine engine = new FusionEngine();
        Evidence movement = Evidence.of("movement-speed", Layer.BEHAVIOR, 0.9, "速度异常");
        FusionEngine.FusionResult without = engine.evaluateDetailed(
                new EvidenceBatch("p-lag", List.of(movement), false, false, Map.of()));
        assertEquals(0.9 * Layer.BEHAVIOR.defaultWeight(), without.score(), DELTA);

        FusionEngine.FusionResult with = engine.evaluateDetailed(
                new EvidenceBatch("p-lag", List.of(movement), false, false, Map.of("lagSpike", "true")));
        assertEquals(0.0, with.score(), DELTA);
    }

    @Test
    void setWeightOverridesLayerContribution() {
        FusionEngine engine = new FusionEngine();
        engine.setWeight(Layer.MEMORY, 0.0);
        assertEquals(0.0, engine.weights().get(Layer.MEMORY), DELTA);
        assertEquals(Verdict.CLEAN, engine.evaluate(batch(memory(0.95))), "权重置零后该层不再贡献分数");
    }

    @Test
    void weightsSnapshotIsDefensive() {
        FusionEngine engine = new FusionEngine();
        Map<Layer, Double> snapshot = engine.weights();
        snapshot.put(Layer.MEMORY, 0.0);
        assertEquals(Verdict.CONFIRMED, engine.evaluate(batch(memory(0.95))),
                "外部改动快照不得影响引擎内部权重");
    }

    @Test
    void mlDisguiseEvidenceAddsBoundedComponent() {
        FusionEngine engine = new FusionEngine();
        Evidence ml = Evidence.of("combat-rotation-ml", Layer.BEHAVIOR, 0.9, "ML 旋转伪装");
        FusionEngine.FusionResult result = engine.evaluateDetailed(batch(ml));
        assertEquals(0.9, result.mlDisguiseScore(), DELTA);
        assertTrue(result.score() > 0.9 * Layer.BEHAVIOR.defaultWeight(),
                "ML 伪装置信度应补足融合总分");
        assertTrue(result.score() <= 1.0);
    }

    @Test
    void absentMlEvidenceLeavesComponentZero() {
        FusionEngine engine = new FusionEngine();
        FusionEngine.FusionResult result = engine.evaluateDetailed(batch(memory(0.95)));
        assertEquals(0.0, result.mlDisguiseScore(), DELTA);
        assertEquals(0.95, result.score(), DELTA);
    }

    private static EvidenceBatch batch(Evidence... evidence) {
        return new EvidenceBatch("p", List.of(evidence), false, false, Map.of());
    }

    private static Evidence memory(double confidence) {
        return Evidence.of("memory-scan", Layer.MEMORY, confidence, "内存篡改");
    }
}
