package com.potatotv.pacc.detection.server.mining;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-04 验收：X-Ray 模拟样本检出率 ≥95%，合法挖矿零误报。
 *
 * <p>X-Ray 样本组合「高稀有矿占比 + 持续正对矿 + 方向集中 / 直线路径」；
 * 合法样本覆盖普通矿工与「稀有矿占比不低但视角分散」两类，后者专门用来压
 * 「只看稀有矿占比」的误报。
 */
class XRayAcceptanceTest {

    private static final int XRAY_SAMPLES = 300;
    private static final int LEGIT_SAMPLES = 10_000;

    private final XRayAnalyzer analyzer = new XRayAnalyzer();

    @Test
    void xrayDetectionRateMeetsTarget() {
        Random random = new Random(20261018L);
        int detected = 0;
        for (int i = 0; i < XRAY_SAMPLES; i++) {
            OreProfile ore = new OreProfile(40 + random.nextInt(41), 18 + random.nextInt(20),
                    150 + random.nextDouble() * 200);
            ViewCorrelationProfile view = new ViewCorrelationProfile(
                    30 + random.nextInt(20),
                    2 + random.nextDouble() * 13,
                    1 + random.nextDouble() * 9,
                    0.2 + random.nextDouble() * 1.0,
                    0.8 + random.nextDouble() * 0.2);
            if (analyzer.evaluate(ore).isPresent() || analyzer.evaluateViewCorrelation(view, ore).isPresent()) {
                detected++;
            }
        }
        double rate = (double) detected / XRAY_SAMPLES;
        System.out.printf("[P-04] X-Ray 检出率=%.4f 样本=%d%n", rate, XRAY_SAMPLES);
        assertTrue(rate >= 0.95, "X-Ray 检出率应 ≥95%，实际 " + rate);
    }

    @Test
    void legitimateMiningStaysClean() {
        Random random = new Random(20261019L);
        int falsePositives = 0;
        for (int i = 0; i < LEGIT_SAMPLES; i++) {
            int total = 30 + random.nextInt(90);
            // 普通矿工：稀有矿占比 1%-20%，探索距离充足
            int rare = (int) Math.floor(total * (0.01 + random.nextDouble() * 0.19));
            OreProfile plain = new OreProfile(total, rare, total * (10 + random.nextDouble() * 20));
            ViewCorrelationProfile plainView = new ViewCorrelationProfile(
                    25 + random.nextInt(30),
                    35 + random.nextDouble() * 90,
                    15 + random.nextDouble() * 45,
                    1.8 + random.nextDouble() * 1.8,
                    0.2 + random.nextDouble() * 0.5);
            if (isHit(plain, plainView)) {
                falsePositives++;
                continue;
            }
            // 高稀有矿占比但视角分散：在矿洞里挑着挖的正常玩家
            int richRare = (int) Math.ceil(total * (0.3 + random.nextDouble() * 0.2));
            OreProfile rich = new OreProfile(total, richRare, total * (10 + random.nextDouble() * 20));
            ViewCorrelationProfile scattered = new ViewCorrelationProfile(
                    25 + random.nextInt(30),
                    30 + random.nextDouble() * 60,
                    20 + random.nextDouble() * 40,
                    2.0 + random.nextDouble() * 1.5,
                    0.2 + random.nextDouble() * 0.6);
            if (isHit(rich, scattered)) {
                falsePositives++;
            }
        }
        double rate = (double) falsePositives / (LEGIT_SAMPLES * 2);
        System.out.printf("[P-04] 合法挖矿误报率=%.6f 样本=%d%n", rate, LEGIT_SAMPLES * 2);
        assertTrue(falsePositives == 0, "合法挖矿不应误报，实际 " + falsePositives);
    }

    private boolean isHit(OreProfile ore, ViewCorrelationProfile view) {
        return analyzer.evaluate(ore).isPresent() || analyzer.evaluateViewCorrelation(view, ore).isPresent();
    }
}
