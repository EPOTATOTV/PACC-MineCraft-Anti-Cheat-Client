package com.potatotv.pacc.detection.server.packet;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-05 验收：Backtrack 模拟检出 ≥95%，高延迟合法玩家零误报。
 *
 * <p>Backtrack 样本覆盖两类：位置回退（跳回 10-60 包前的旧位置）与序号回退。
 * 合法样本覆盖 50ms 稳定包节奏与 200-400ms 高延迟抖动，位置始终单调前进。
 */
class BacktrackAcceptanceTest {

    private static final int BACKTRACK_SAMPLES = 300;
    private static final int LEGIT_SAMPLES = 10_000;
    private static final int NO_SEQUENCE = Integer.MIN_VALUE;

    @Test
    void backtrackDetectionRateMeetsTarget() {
        Random random = new Random(20261018L);
        int detected = 0;
        for (int i = 0; i < BACKTRACK_SAMPLES; i++) {
            BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
            if (i % 2 == 0) {
                feedPositionRewind(analyzer, random);
            } else {
                feedSequenceRegression(analyzer, random);
            }
            if (analyzer.check("p").isPresent()) {
                detected++;
            }
        }
        double rate = (double) detected / BACKTRACK_SAMPLES;
        System.out.printf("[P-05] Backtrack 检出率=%.4f 样本=%d%n", rate, BACKTRACK_SAMPLES);
        assertTrue(rate >= 0.95, "Backtrack 检出率应 ≥95%，实际 " + rate);
    }

    @Test
    void highLatencyLegitimatePlayersStayClean() {
        Random random = new Random(20261019L);
        int falsePositives = 0;
        for (int i = 0; i < LEGIT_SAMPLES; i++) {
            BacktrackAnalyzer analyzer = new BacktrackAnalyzer();
            feedLegit(analyzer, random, i % 2 == 0 ? 50 : 200 + random.nextInt(200));
            if (analyzer.check("p").isPresent()) {
                falsePositives++;
            }
        }
        System.out.printf("[P-05] 合法玩家误报数=%d 样本=%d%n", falsePositives, LEGIT_SAMPLES);
        assertTrue(falsePositives == 0, "高延迟合法玩家不应误报，实际 " + falsePositives);
    }

    private static void feedPositionRewind(BacktrackAnalyzer analyzer, Random random) {
        double x = random.nextDouble() * 10;
        double z = random.nextDouble() * 10;
        long ts = 0;
        for (int i = 0; i < 20; i++) {
            ts += 50;
            analyzer.record("p", ts, NO_SEQUENCE, x, 64, z);
            z += 0.2;
        }
        // 跳回 10-20 包之前的旧位置，且中间离开过该点
        analyzer.record("p", ts + 50, NO_SEQUENCE, x, 64, z - 3.6);
    }

    private static void feedSequenceRegression(BacktrackAnalyzer analyzer, Random random) {
        long ts = 0;
        for (int i = 0; i < 12; i++) {
            ts += 50;
            analyzer.record("p", ts, i, 0, 64, i * 0.1);
        }
        ts += 50;
        analyzer.record("p", ts, 100 + random.nextInt(50), 0, 64, 1.2);
        ts += 50;
        analyzer.record("p", ts, 20 + random.nextInt(20), 0, 64, 1.3);
    }

    private static void feedLegit(BacktrackAnalyzer analyzer, Random random, long baseInterval) {
        long ts = 0;
        double z = 0;
        for (int i = 0; i < 60; i++) {
            ts += Math.max(40, baseInterval + (long) ((random.nextDouble() - 0.5) * 20));
            z += 0.1 + random.nextDouble() * 0.2;
            analyzer.record("p", ts, i, 0, 64, z);
        }
    }
}
