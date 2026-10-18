package com.potatotv.pacc.detection.server.packet;

import com.potatotv.pacc.detection.fusion.Evidence;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-05 Backtrack / FakeLag 检测：压包突发、位置回退与合法匀速移动。
 */
class BacktrackAnalyzerTest {

    private static final int NO_SEQUENCE = Integer.MIN_VALUE;

    @Test
    void heldPacketsBurstingOutFlagFakeLag() {
        BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
        for (int i = 0; i < 10; i++) {
            analyzer.record("p", i * 50L, NO_SEQUENCE, 0, 64, i);
        }
        // 停 1 秒后连续 4 个包在同一毫秒段到达
        analyzer.record("p", 1500L, NO_SEQUENCE, 0, 64, 10);
        analyzer.record("p", 1501L, NO_SEQUENCE, 0, 64, 11);
        analyzer.record("p", 1502L, NO_SEQUENCE, 0, 64, 12);
        analyzer.record("p", 1503L, NO_SEQUENCE, 0, 64, 13);

        Evidence evidence = analyzer.check("p").orElseThrow();
        assertTrue(evidence.detail().contains("FakeLag"));
        assertTrue(evidence.confidence() >= 0.6);
    }

    @Test
    void staleSequenceNumberFlagsBacktrack() {
        BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
        for (int i = 0; i < 12; i++) {
            analyzer.record("p", i * 50L, i, 0, 64, 0);
        }
        analyzer.record("p", 600L, 100, 0, 64, 0);
        analyzer.record("p", 650L, 50, 0, 64, 0);   // 序号倒退 50，超过容差

        Evidence evidence = analyzer.check("p").orElseThrow();
        assertTrue(evidence.detail().contains("Backtrack"));
        assertTrue(evidence.detail().contains("序号回退=1"));
    }

    @Test
    void jumpingBackToOldPositionFlagsBacktrack() {
        BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
        for (int i = 0; i < 12; i++) {
            analyzer.record("p", i * 50L, NO_SEQUENCE, 0, 64, i);
        }
        analyzer.record("p", 600L, NO_SEQUENCE, 0, 64, 0);   // 跳回起点

        Evidence evidence = analyzer.check("p").orElseThrow();
        assertTrue(evidence.detail().contains("位置回退=是"));
    }

    @Test
    void steadyMovementIsClean() {
        BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
        for (int i = 0; i < 40; i++) {
            analyzer.record("p", i * 50L, i, i * 0.3, 64, i * 0.4);
            assertTrue(analyzer.check("p").isEmpty(), "正常移动不应出证");
        }
    }

    @Test
    void highLatencySteadyCadenceIsClean() {
        BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
        for (int i = 0; i < 30; i++) {
            analyzer.record("p", i * 350L, NO_SEQUENCE, 0, 64, i * 0.2);
        }
        Optional<Evidence> evidence = analyzer.check("p");
        assertTrue(evidence.isEmpty(), "稳定高延迟不应被判成压包");
    }

    @Test
    void resetClearsWindow() {
        BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
        for (int i = 0; i < 12; i++) {
            analyzer.record("p", i * 50L, i, 0, 64, i);
        }
        analyzer.reset("p");
        assertEquals(Optional.empty(), analyzer.check("p"));
    }
}
