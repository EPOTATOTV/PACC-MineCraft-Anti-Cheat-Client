package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.XRayCheck;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class XRayCheckTest {

    @Test
    void shortPathWithRareOresFlags() {
        XRayCheck check = new XRayCheck();
        travel(check, 3, 20.0);
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 40; i++) {
            results = check.onBlockBreak("p1", "DEEPSLATE_DIAMOND_ORE", new Position(0, -50, 0), 1000L + i);
        }
        assertTrue(results.stream().anyMatch(r -> "xray-mining".equals(r.cheatType())));
    }

    @Test
    void normalMiningIsClean() {
        XRayCheck check = new XRayCheck();
        travel(check, 90, 20.0);
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 40; i++) {
            String block = i % 10 == 0 ? "DIAMOND_ORE" : "COAL_ORE";
            results = check.onBlockBreak("p1", block, new Position(0, 30, 0), 1000L + i);
        }
        assertTrue(results.isEmpty());
    }

    @Test
    void nonOreBlocksAreIgnored() {
        XRayCheck check = new XRayCheck();
        travel(check, 1, 5.0);
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 100; i++) {
            results = check.onBlockBreak("p1", "STONE", new Position(0, 64, 0), 1000L + i);
        }
        assertTrue(results.isEmpty());
    }

    @Test
    void resetClearsStatistics() {
        XRayCheck check = new XRayCheck();
        travel(check, 3, 10.0);
        for (int i = 0; i < 40; i++) {
            check.onBlockBreak("p1", "DIAMOND_ORE", new Position(0, -50, 0), 1000L + i);
        }
        check.reset("p1");
        assertTrue(check.onBlockBreak("p1", "DIAMOND_ORE", new Position(0, -50, 0), 2000L).isEmpty());
    }

    /** 以固定步长走若干步，累计探索距离。 */
    private static void travel(XRayCheck check, int steps, double stepBlocks) {
        for (int i = 0; i < steps; i++) {
            double z = i * stepBlocks;
            PlayerPosition from = new PlayerPosition("p1", new Position(0, 64, z), 0f, 0f, 1000L + i,
                    true, GameMode.SURVIVAL);
            PlayerPosition to = new PlayerPosition("p1", new Position(0, 64, z + stepBlocks), 0f, 0f,
                    1001L + i, true, GameMode.SURVIVAL);
            check.onMoveObserved(from, to);
        }
    }
}
