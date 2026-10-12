package com.potatotv.paccclient.detection.scanner;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintMatch;
import com.potatotv.pacc.detection.signature.FingerprintType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎指纹库检测器裁决逻辑测试：单/双通道门槛与风险等级映射。
 */
class EngineFingerprintDetectorTest {

    private static FingerprintMatch hit(String id, int riskLevel, double confidence) {
        CheatFingerprint fp = new CheatFingerprint(
                id, FingerprintType.PROCESS, id,
                List.of("cheat"), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.CLIENT, riskLevel);
        return new FingerprintMatch(fp, FingerprintType.PROCESS, "cheat", confidence);
    }

    @Test
    void 高风险单通道命中即可上报() {
        Optional<EngineFingerprintDetector.ScoredHit> verdict =
                EngineFingerprintDetector.evaluate(List.of(hit("vape-v4", 5, 0.9)), List.of());
        assertTrue(verdict.isPresent());
        assertEquals(5, verdict.get().riskLevel());
        assertEquals(90, Math.round(verdict.get().confidence() * 100));
    }

    @Test
    void 低风险单通道命中不上报() {
        assertTrue(EngineFingerprintDetector.evaluate(List.of(hit("low-risk-client", 3, 0.8)), List.of()).isEmpty());
    }

    @Test
    void 低风险双通道印证后上报() {
        Optional<EngineFingerprintDetector.ScoredHit> verdict = EngineFingerprintDetector.evaluate(
                List.of(hit("wurst-client", 3, 0.8)),
                List.of(hit("wurst-client", 3, 0.85)));
        assertTrue(verdict.isPresent());
        assertEquals(0.85, verdict.get().confidence(), 1e-9);
    }

    @Test
    void 无命中为空() {
        assertTrue(EngineFingerprintDetector.evaluate(List.of(), List.of()).isEmpty());
    }
}
