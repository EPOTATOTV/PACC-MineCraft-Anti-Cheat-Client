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
}
