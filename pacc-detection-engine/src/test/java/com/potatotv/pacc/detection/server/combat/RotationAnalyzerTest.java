package com.potatotv.pacc.detection.server.combat;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.profile.RotationSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-02 旋转分析回归：基础机器旋转与 ML 伪装应分别落到不同检测器，
 * 样本不足或人类噪声不出证。
 */
class RotationAnalyzerTest {

    @Test
    void insufficientSamplesProduceNoEvidence() {
        RotationAnalyzer analyzer = new RotationAnalyzer();
        for (int i = 0; i < 5; i++) {
            analyzer.record("p", new RotationSample(i * 30f, 0f, i * 50L, true));
        }
        assertTrue(analyzer.check("p").isEmpty());
    }

    @Test
    void uniformRotationFlagsBaseDetector() {
        RotationAnalyzer analyzer = new RotationAnalyzer();
        analyzer.record("p", smoothRotation(30, 30f));
        Evidence evidence = analyzer.check("p").orElseThrow();
        assertEquals(RotationAnalyzer.DETECTOR_ID, evidence.detectorId());
        assertTrue(evidence.confidence() >= RotationAnalyzer.EVIDENCE_THRESHOLD);
    }

    @Test
    void mlDisguiseFlagsDedicatedDetector() {
        RotationAnalyzer analyzer = new RotationAnalyzer();
        analyzer.record("p", mlDisguisedRotation());
        Evidence evidence = analyzer.check("p").orElseThrow();
        assertEquals(RotationAnalyzer.ML_DETECTOR_ID, evidence.detectorId());
        assertTrue(evidence.detail().contains("旋转-CPS相关"));
    }

    @Test
    void noisyHumanRotationProducesNoEvidence() {
        RotationAnalyzer analyzer = new RotationAnalyzer();
        analyzer.record("p", noisyHumanRotation());
        Optional<Evidence> evidence = analyzer.check("p");
        assertTrue(evidence.isEmpty(), "人类抖动不应被旋转分析出证");
    }

    @Test
    void resetClearsWindow() {
        RotationAnalyzer analyzer = new RotationAnalyzer();
        analyzer.record("p", smoothRotation(30, 30f));
        analyzer.reset("p");
        assertTrue(analyzer.check("p").isEmpty());
    }

    private static List<RotationSample> smoothRotation(int count, float stepDeg) {
        List<RotationSample> samples = new ArrayList<>();
        float yaw = 0f;
        for (int i = 0; i < count; i++) {
            samples.add(new RotationSample(yaw, 0f, i * 50L, true));
            yaw += stepDeg;
        }
        return samples;
    }

    private static List<RotationSample> mlDisguisedRotation() {
        List<RotationSample> samples = new ArrayList<>();
        float yaw = 0f;
        for (int i = 0; i < 120; i++) {
            float step = (float) (10.0 + 25.0 * Math.sin(2 * Math.PI * i / 20.0));
            samples.add(new RotationSample(yaw, 0f, i * 50L, step > 10f));
            yaw += step;
        }
        return samples;
    }

    private static List<RotationSample> noisyHumanRotation() {
        float[] deltas = {25, -30, 20, -35, 28, -22, 33, -26, 24, -31,
                27, -29, 23, -34, 21, -32, 26, -28, 30, -25};
        List<RotationSample> samples = new ArrayList<>();
        float yaw = 0f;
        for (int i = 0; i <= deltas.length; i++) {
            samples.add(new RotationSample(yaw, 0f, i * 50L, true));
            if (i < deltas.length) {
                yaw += deltas[i];
            }
        }
        return samples;
    }
}
