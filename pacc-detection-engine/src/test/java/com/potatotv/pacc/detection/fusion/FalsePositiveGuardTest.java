package com.potatotv.pacc.detection.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FalsePositiveGuard} 误报抑制测试：环境降权系数与豁免过滤规则。
 */
class FalsePositiveGuardTest {

    private static final double DELTA = 1e-9;

    @Test
    void factorsMatchDocumentedValues() {
        assertEquals(0.6, FalsePositiveGuard.NETWORK_ANOMALY_FACTOR, DELTA);
        assertEquals(0.5, FalsePositiveGuard.PLUGIN_INTERFERENCE_FACTOR, DELTA);
    }

    @Test
    void suppressAppliesNetworkFactor() {
        assertEquals(0.6, FalsePositiveGuard.suppress(1.0, flag(true, false)), DELTA);
    }

    @Test
    void suppressAppliesPluginFactor() {
        assertEquals(0.5, FalsePositiveGuard.suppress(1.0, flag(false, true)), DELTA);
    }

    @Test
    void suppressMultipliesBothFactors() {
        assertEquals(0.3, FalsePositiveGuard.suppress(1.0, flag(true, true)), DELTA);
    }

    @Test
    void suppressClampsToUnitRange() {
        assertEquals(1.0, FalsePositiveGuard.suppress(2.0, EvidenceBatch.empty("p")), DELTA);
        assertEquals(0.0, FalsePositiveGuard.suppress(-1.0, flag(true, true)), DELTA);
    }

    @Test
    void creativeContextDropsPredictionAndMovementEvidence() {
        Evidence prediction = Evidence.of("prediction-fly", Layer.BEHAVIOR, 0.9, "上升");
        Evidence movement = Evidence.of("movement-speed", Layer.BEHAVIOR, 0.9, "速度");
        Evidence moduleTamper = Evidence.of("module-scan", Layer.MODULE, 0.9, "注入模块");
        EvidenceBatch batch = new EvidenceBatch(
                "p", List.of(prediction, movement, moduleTamper), false, false, Map.of("creative", "true"));
        List<Evidence> filtered = FalsePositiveGuard.filterExempted(batch);
        assertEquals(1, filtered.size());
        assertEquals("module-scan", filtered.get(0).detectorId(), "创造模式只豁免行为层移动/预测证据");
    }

    @Test
    void flightAllowedBehavesLikeCreative() {
        Evidence prediction = Evidence.of("prediction-jump", Layer.BEHAVIOR, 0.9, "跳跃");
        EvidenceBatch batch = new EvidenceBatch(
                "p", List.of(prediction), false, false, Map.of("flightAllowed", "1"));
        assertTrue(FalsePositiveGuard.filterExempted(batch).isEmpty(), "飞行权限同样豁免预测证据");
    }

    @Test
    void lagSpikeDropsNetworkAndMovementEvidence() {
        Evidence network = Evidence.of("proxy-detect", Layer.NETWORK, 0.9, "代理工具");
        Evidence movement = Evidence.of("packet-timing", Layer.BEHAVIOR, 0.9, "包时序");
        Evidence memory = Evidence.of("memory-scan", Layer.MEMORY, 0.9, "内存篡改");
        EvidenceBatch batch = new EvidenceBatch(
                "p", List.of(network, movement, memory), false, false, Map.of("lagSpike", "true"));
        List<Evidence> filtered = FalsePositiveGuard.filterExempted(batch);
        assertEquals(1, filtered.size());
        assertEquals("memory-scan", filtered.get(0).detectorId(), "延迟尖峰只豁免网络与移动类证据");
    }

    @Test
    void normalContextKeepsAllEvidence() {
        Evidence network = Evidence.of("proxy-detect", Layer.NETWORK, 0.9, "代理工具");
        Evidence movement = Evidence.of("movement-speed", Layer.BEHAVIOR, 0.9, "速度");
        EvidenceBatch batch = new EvidenceBatch(
                "p", List.of(network, movement), false, false, Map.of());
        assertEquals(2, FalsePositiveGuard.filterExempted(batch).size());
    }

    @Test
    void contextFlagTruthiness() {
        assertTrue(new EvidenceBatch("p", List.of(), false, false, Map.of("creative", "yes")).boolFlag("creative"));
        assertFalse(new EvidenceBatch("p", List.of(), false, false, Map.of("creative", "0")).boolFlag("creative"));
        assertEquals("", EvidenceBatch.empty("p").flag("creative"));
    }

    private static EvidenceBatch flag(boolean network, boolean plugin) {
        return new EvidenceBatch("p", List.of(), network, plugin, Map.of());
    }
}
