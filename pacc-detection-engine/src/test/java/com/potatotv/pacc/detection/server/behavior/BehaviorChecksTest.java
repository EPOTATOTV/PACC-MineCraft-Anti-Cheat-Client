package com.potatotv.pacc.detection.server.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.potatotv.pacc.detection.fusion.ConfidenceScorer;
import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.profile.RotationSample;

class BehaviorChecksTest {

    private static Evidence checked(Optional<Evidence> evidence) {
        assertTrue(evidence.isPresent(), "应产出证据");
        return evidence.get();
    }

    @Test
    void statOutlierFlags3SigmaDeviation() {
        StatOutlier outlier = new StatOutlier();
        // 基线未成型（< 32 样本）不出证
        for (int i = 0; i < 10; i++) {
            outlier.record("cold", 0.1);
        }
        outlier.record("cold", 0.5);
        assertTrue(outlier.check("cold").isEmpty());
        // 基线成型后偏离 3σ 出证 0.5
        for (int i = 0; i < 40; i++) {
            outlier.record("p", 0.1);
        }
        assertTrue(outlier.check("p").isEmpty());
        outlier.record("p", 0.5);
        Evidence evidence = checked(outlier.check("p"));
        assertEquals("behavior-outlier", evidence.detectorId());
        assertEquals(Layer.BEHAVIOR, evidence.layer());
        assertEquals(0.5, evidence.confidence());
        assertTrue(evidence.detail().contains("3σ"));
        // 回到基线水平后不再出证
        for (int i = 0; i < 5; i++) {
            outlier.record("p", 0.1);
        }
        assertTrue(outlier.check("p").isEmpty());
        outlier.reset("p");
    }

    @Test
    void patternClusterFlagsStrictPeriodicDigging() {
        PatternCluster cluster = new PatternCluster();
        // 挖 4 tick + 停 4 tick 严格循环 → 周期 8 的自相关 = 1.0
        long ts = 0;
        for (int i = 0; i < 64; i++) {
            boolean digging = i % 8 < 4;
            cluster.record("p", 0.0, 0.0, digging, false, ts);
            ts += 50;
        }
        Evidence evidence = checked(cluster.check("p"));
        assertEquals("behavior-cluster", evidence.detectorId());
        assertEquals(0.7, evidence.confidence());
        assertTrue(evidence.detail().contains("周期=8"));
        assertTrue(evidence.detail().contains("自相关=1.00"));
    }

    @Test
    void patternClusterFlagsBaritoneStraightLine() {
        PatternCluster cluster = new PatternCluster();
        // 250 tick 恒定朝向直线移动（12.5s > 10s），窗口只有 60 tick 也应捕获
        long ts = 0;
        for (int i = 0; i < 250; i++) {
            cluster.record("p", 0.2, 0.0, false, false, ts);
            ts += 50;
        }
        Evidence evidence = checked(cluster.check("p"));
        assertEquals(0.6, evidence.confidence());
        assertTrue(evidence.detail().contains("Baritone"));
    }

    @Test
    void patternClusterHumanJitterIsClean() {
        PatternCluster cluster = new PatternCluster();
        // 速度与朝向带抖动、动作穿插 → 无周期、无长直线
        double[] speeds = {0.0, 0.15, 0.21, 0.05, 0.18, 0.0, 0.2, 0.12};
        double[] yaws = {0.0, 3.0, -4.0, 0.2, 6.0, -2.0, 0.4, 1.5};
        long ts = 0;
        for (int i = 0; i < 64; i++) {
            cluster.record("p", speeds[i % speeds.length], yaws[i % yaws.length],
                    i % 13 == 0, i % 17 == 0, ts);
            ts += 50;
        }
        assertTrue(cluster.check("p").isEmpty());
        cluster.reset("p");
        assertTrue(cluster.check("p").isEmpty());
    }

    @Test
    void lstmForwardProducesProbabilityShape() {
        MLDetector detector = new MLDetector(3, 4, LstmWeights.random(3, 4, 7L));
        List<double[]> sequence = List.of(
                new double[] {0.1, 0.2, 0.3},
                new double[] {0.4, 0.1, 0.9},
                new double[] {0.2, 0.7, 0.0},
                new double[] {0.9, 0.3, 0.5},
                new double[] {0.0, 0.6, 0.8});
        double[] output = detector.forward(sequence);
        assertEquals(1, output.length);
        assertTrue(output[0] > 0.0 && output[0] < 1.0);
        // 空序列回退中性概率
        assertEquals(0.5, detector.forward(List.of())[0]);
    }

    @Test
    void lstmWeightsFlatRoundTrip() {
        LstmWeights original = LstmWeights.random(3, 4, 42L);
        double[] flat = original.toFlat();
        LstmWeights restored = LstmWeights.fromFlat(flat, 3, 4);
        List<double[]> sequence = List.of(new double[] {0.5, 0.25, 0.75}, new double[] {0.1, 0.8, 0.3});
        double[] expected = new MLDetector(3, 4, original).forward(sequence);
        double[] actual = new MLDetector(3, 4, restored).forward(sequence);
        assertEquals(expected[0], actual[0], 0.0);
        // 长度不符必须抛错
        boolean threw = false;
        try {
            LstmWeights.fromFlat(new double[flat.length - 1], 3, 4);
        } catch (IllegalArgumentException illegal) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    void classifyRespectsMlGate() {
        // 随机权重无判别力：sigmoid 输入 |z| ≤ 0.6 → p < 0.95 门槛，必不出证
        MLDetector randomWeights = new MLDetector(3, 4, LstmWeights.random(3, 4, 7L));
        List<double[]> sequence = List.of(new double[] {0.2, 0.5, 0.9}, new double[] {0.7, 0.1, 0.3});
        assertTrue(randomWeights.classify("p", sequence).isEmpty());
        // 全零权重 + 大输出偏置 → p = sigmoid(10) ≈ 0.99995 ≥ 0.95 → 出证
        int expectedLength = 4 * (4 * 3 + 4 * 4 + 4) + 4 + 1;
        double[] flat = new double[expectedLength];
        flat[expectedLength - 1] = 10.0;
        MLDetector trained = new MLDetector(3, 4, LstmWeights.fromFlat(flat, 3, 4));
        Evidence evidence = checked(trained.classify("p", sequence));
        assertEquals("behavior-ml", evidence.detectorId());
        assertTrue(evidence.confidence() >= ConfidenceScorer.DEFAULT_ML_GATE);
        assertTrue(evidence.detail().contains("lstm p="));
        // 特征工具维度
        List<double[]> clicks = MLDetector.clickFeatures(List.of(500L, 400L));
        assertEquals(2, clicks.size());
        assertEquals(3, clicks.get(0).length);
        assertEquals(0.5, clicks.get(0)[0]);
        assertEquals(-0.1, clicks.get(1)[1], 1e-12);
        assertEquals(0.01, clicks.get(1)[2], 1e-12);
        List<double[]> rotations = MLDetector.rotationFeatures(
                List.of(new RotationSample(0f, 0f, 0L, false), new RotationSample(45f, 0f, 100L, true)));
        assertEquals(2, rotations.size());
        assertEquals(3, rotations.get(1).length);
    }

    @Test
    void correlationEngineMergesMultiDetectorEvidence() {
        CorrelationEngine engine = new CorrelationEngine();
        // 单维度：不出证
        Evidence single = Evidence.of("combat-reach", Layer.BEHAVIOR, 0.66, "reach=3.05");
        assertTrue(engine.correlate("p", List.of(single)).isEmpty());
        assertTrue(engine.correlate("p", List.of()).isEmpty());
        assertTrue(engine.correlate("p", null).isEmpty());
        // 同一检测器两条：仍算单维度
        assertTrue(engine.correlate("p", List.of(single, single)).isEmpty());
        // 两个不同检测器：noisy-OR 合并
        Evidence rotation = Evidence.of("combat-rotation", Layer.BEHAVIOR, 0.7, "机器旋转");
        Evidence merged = checked(engine.correlate("p", List.of(single, rotation)));
        assertEquals("behavior-correlation", merged.detectorId());
        assertEquals(Layer.BEHAVIOR, merged.layer());
        double expected = ConfidenceScorer.combine(0.66, 0.7);
        assertEquals(expected, merged.confidence(), 1e-12);
        assertTrue(merged.confidence() >= 0.7);
        assertTrue(merged.detail().contains("combat-reach(x1)"));
        assertTrue(merged.detail().contains("combat-rotation(x1)"));
    }
}
