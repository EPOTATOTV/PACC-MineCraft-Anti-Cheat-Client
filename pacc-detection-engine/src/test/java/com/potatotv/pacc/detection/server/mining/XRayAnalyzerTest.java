package com.potatotv.pacc.detection.server.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XRayAnalyzerTest {

    private final XRayAnalyzer analyzer = new XRayAnalyzer();

    @Test
    void parsesDeepslateAndNetherVariants() {
        assertEquals(OreType.DIAMOND, OreType.fromBlock("DEEPSLATE_DIAMOND_ORE"));
        assertEquals(OreType.GOLD, OreType.fromBlock("NETHER_GOLD_ORE"));
        assertEquals(OreType.COPPER, OreType.fromBlock("DEEPSLATE_COPPER_ORE"));
        assertEquals(OreType.ANCIENT_DEBRIS, OreType.fromBlock("ANCIENT_DEBRIS"));
        assertNull(OreType.fromBlock("STONE"));
        assertNull(OreType.fromBlock(null));
    }

    @Test
    void rareFlagsMatchDesign() {
        assertTrue(OreType.DIAMOND.rare());
        assertTrue(OreType.EMERALD.rare());
        assertTrue(OreType.ANCIENT_DEBRIS.rare());
        assertFalse(OreType.COAL.rare());
        assertFalse(OreType.IRON.rare());
    }

    @Test
    void insufficientSamplesAreNotJudged() {
        assertTrue(analyzer.evaluate(new OreProfile(10, 10, 5)).isEmpty());
    }

    @Test
    void missingTravelDataAbortsJudgement() {
        assertTrue(analyzer.evaluate(new OreProfile(60, 40, 0)).isEmpty());
    }

    @Test
    void highRareRatioWithShortPathFlags() {
        // 60 块矿里 40 块稀有，探索 60 格 → 每块矿 1 格，典型透视直挖
        assertTrue(analyzer.evaluate(new OreProfile(60, 40, 60)).isPresent());
    }

    @Test
    void normalMiningIsClean() {
        // 60 块矿里 6 块稀有（10%），探索 900 格 → 每块矿 15 格
        assertTrue(analyzer.evaluate(new OreProfile(60, 6, 900)).isEmpty());
    }

    @Test
    void singleConditionIsNotEnough() {
        // 稀有占比高但走了很多路：更像在矿洞里挑着挖，不判
        assertTrue(analyzer.evaluate(new OreProfile(40, 20, 1200)).isEmpty());
        // 路很短但稀有占比正常：可能只是贴着矿脉挖，不判
        assertTrue(analyzer.evaluate(new OreProfile(80, 8, 80)).isEmpty());
    }

    @Test
    void statsAggregateAndReset() {
        OreDiscoveryStats stats = new OreDiscoveryStats();
        stats.recordOre("p1", "DIAMOND_ORE");
        stats.recordOre("p1", "STONE");
        stats.recordOre("p1", "COAL_ORE");
        stats.recordTravel("p1", 10);
        stats.recordTravel("p1", 40);   // 超过单次上限，视为传送，丢弃
        stats.recordTravel("p1", -1);   // 非正数丢弃
        OreProfile profile = stats.profile("p1");
        assertEquals(2, profile.totalOres());
        assertEquals(1, profile.rareOres());
        assertEquals(10.0, profile.travelledBlocks(), 1e-9);
        stats.reset("p1");
        assertEquals(0, stats.profile("p1").totalOres());
    }

    @Test
    void viewCorrelationFlagsMechanicalAiming() {
        ViewCorrelationProfile view = new ViewCorrelationProfile(30, 5.0, 3.0, 0.5, 0.9);
        assertTrue(analyzer.evaluateViewCorrelation(view, new OreProfile(40, 20, 400)).isPresent());
    }

    @Test
    void viewCorrelationRequiresRareOreShare() {
        ViewCorrelationProfile view = new ViewCorrelationProfile(30, 5.0, 3.0, 0.5, 0.9);
        // 40 块矿里只有 4 块稀有（10%），普通矿工不看这个维度
        assertTrue(analyzer.evaluateViewCorrelation(view, new OreProfile(40, 4, 400)).isEmpty());
    }

    @Test
    void scatteredHumanViewIsClean() {
        ViewCorrelationProfile view = new ViewCorrelationProfile(30, 45.0, 30.0, 2.8, 0.4);
        assertTrue(analyzer.evaluateViewCorrelation(view, new OreProfile(40, 20, 400)).isEmpty());
    }

    @Test
    void missingViewDataAbortsViewJudgement() {
        assertTrue(analyzer.evaluateViewCorrelation(
                ViewCorrelationProfile.empty(), new OreProfile(40, 20, 400)).isEmpty());
    }

    @Test
    void alignmentComputesBukkitYawConvention() {
        assertEquals(0.0, OreDiscoveryStats.alignmentDeg(0, 0, 0f, 0, 5), 1e-9);
        assertEquals(90.0, OreDiscoveryStats.alignmentDeg(0, 0, 0f, -5, 0), 1e-9);
        assertEquals(0.0, OreDiscoveryStats.alignmentDeg(0, 0, 90f, -5, 0), 1e-9);
        assertEquals(0.0, OreDiscoveryStats.alignmentDeg(0, 0, 270f, 5, 0), 1e-9);
    }

    @Test
    void entropyAndIqrAreComputedFromDistribution() {
        assertEquals(0.0, OreDiscoveryStats.entropyBits(java.util.List.of(10.0, 10.0, 10.0)), 1e-9);
        assertEquals(0.0, OreDiscoveryStats.iqr(java.util.List.of(1.0, 2.0, 3.0)), 1e-9);
        assertTrue(OreDiscoveryStats.iqr(java.util.List.of(1.0, 2.0, 8.0, 9.0)) > 5.0);
    }

    @Test
    void viewProfileUsesPositionYawAndOreCoordinates() {
        OreDiscoveryStats stats = new OreDiscoveryStats();
        for (int i = 0; i < 30; i++) {
            double z = i * 5.0;
            stats.recordPosition("p1", 0, z, 0f);
            stats.recordTravel("p1", 5.0);
            stats.recordOre("p1", "DIAMOND_ORE", 0, z + 2.0);
        }
        ViewCorrelationProfile view = stats.viewProfile("p1");
        assertEquals(30, view.samples());
        assertEquals(0.0, view.meanAlignmentDeg(), 1e-6);
        assertEquals(0.0, view.yawEntropyBits(), 1e-9);
        assertEquals(145.0 / 150.0, view.pathStraightness(), 1e-9);
    }

    @Test
    void pathProfileTracksStraightTunnel() {
        OreDiscoveryStats stats = new OreDiscoveryStats();
        for (int i = 0; i < 40; i++) {
            stats.recordPosition("p1", 0, i * 4.0, 0f);
            stats.recordTravel("p1", 4.0);
        }
        PathProfile path = stats.pathProfile("p1");
        assertEquals(160.0, path.travelledBlocks(), 1e-9);
        assertEquals(0.0, path.turnRate(), 1e-9);
        assertTrue(path.longestStraightBlocks() >= 150.0);
    }

    @Test
    void pathProfileCountsFrequentTurns() {
        OreDiscoveryStats stats = new OreDiscoveryStats();
        double[][] corners = {{0, 0}, {4, 0}, {4, 4}, {0, 4}};
        for (int i = 0; i < 40; i++) {
            double[] p = corners[i % 4];
            stats.recordPosition("p1", p[0], p[1], 0f);
            stats.recordTravel("p1", 4.0);
        }
        PathProfile path = stats.pathProfile("p1");
        assertTrue(path.turnRate() > 0.5, "绕圈路径应被识别为频繁转向");
        assertTrue(path.longestStraightBlocks() <= 8.0);
    }

    @Test
    void pathAnalysisFlagsStraightOreSeeking() {
        PathProfile path = new PathProfile(300, 0.02, 250);
        assertTrue(analyzer.evaluatePath(path, new OreProfile(40, 20, 300)).isPresent());
    }

    @Test
    void pathAnalysisRejectsTurningOrCommonOre() {
        OreProfile rare = new OreProfile(40, 20, 300);
        assertTrue(analyzer.evaluatePath(new PathProfile(300, 0.5, 250), rare).isEmpty());
        assertTrue(analyzer.evaluatePath(new PathProfile(300, 0.02, 40), rare).isEmpty());
        assertTrue(analyzer.evaluatePath(new PathProfile(50, 0.02, 40), rare).isEmpty());
        assertTrue(analyzer.evaluatePath(new PathProfile(300, 0.02, 250),
                new OreProfile(40, 4, 300)).isEmpty());
    }
}
