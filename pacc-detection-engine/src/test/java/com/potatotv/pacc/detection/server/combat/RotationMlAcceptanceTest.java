package com.potatotv.pacc.detection.server.combat;

import com.potatotv.pacc.detection.profile.RotationSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-02 验收：ML 旋转伪装模拟样本检出率 ≥80%，合法玩家误报率 ≤0.01%。
 *
 * <p>样本由确定性随机源生成：伪装样本是「低 jerk 正弦转头 + 攻击同拍 + 长窗口重复节拍」，
 * 合法样本是「方向反复、步长抖动、攻击帧与转速无关」。指标口径与文档 §6 一致。
 */
class RotationMlAcceptanceTest {

    private static final int DISGUISE_SAMPLES = 200;
    private static final int LEGIT_SAMPLES = 10_000;
    private static final int FRAMES = 120;

    @Test
    void mlDisguiseDetectionRateMeetsTarget() {
        Random random = new Random(20261018L);
        int detected = 0;
        for (int i = 0; i < DISGUISE_SAMPLES; i++) {
            RotationAnalyzer analyzer = new RotationAnalyzer();
            analyzer.record("p" + i, disguise(random));
            if (analyzer.check("p" + i).isPresent()) {
                detected++;
            }
        }
        double rate = (double) detected / DISGUISE_SAMPLES;
        System.out.printf("[P-02] ML 伪装检出率=%.4f 样本=%d%n", rate, DISGUISE_SAMPLES);
        assertTrue(rate >= 0.80, "ML 伪装检出率应 ≥80%，实际 " + rate);
    }

    @Test
    void legitimatePlayersStayBelowFalsePositiveTarget() {
        Random random = new Random(20261019L);
        int falsePositives = 0;
        for (int i = 0; i < LEGIT_SAMPLES; i++) {
            RotationAnalyzer analyzer = new RotationAnalyzer();
            analyzer.record("p" + i, human(random));
            if (analyzer.check("p" + i).isPresent()) {
                falsePositives++;
            }
        }
        double rate = (double) falsePositives / LEGIT_SAMPLES;
        System.out.printf("[P-02] 合法玩家误报率=%.6f 样本=%d%n", rate, LEGIT_SAMPLES);
        assertTrue(rate <= 0.0001, "合法玩家误报率应 ≤0.01%，实际 " + rate);
    }

    /** ML 转头：正弦速度曲线 + 攻击帧只落在高转速段 + 幅度/周期随机化。 */
    private static List<RotationSample> disguise(Random random) {
        double base = 8.0 + random.nextDouble() * 7.0;
        double amplitude = 20.0 + random.nextDouble() * 10.0;
        int period = 15 + random.nextInt(10);
        double phase = random.nextDouble() * Math.PI * 2;
        double jitter = random.nextDouble() * 0.8;
        List<RotationSample> samples = new ArrayList<>(FRAMES);
        float yaw = (float) (random.nextDouble() * 360.0);
        for (int i = 0; i < FRAMES; i++) {
            double step = base + amplitude * Math.sin(phase + 2 * Math.PI * i / period)
                    + (random.nextDouble() - 0.5) * jitter;
            boolean attacking = step > base + amplitude * 0.4;
            samples.add(new RotationSample(yaw, 0f, i * 50L, attacking));
            yaw += (float) Math.max(0.0, step);
        }
        return samples;
    }

    /** 合法玩家：方向左右修正、步长随机、偶尔停顿；攻击帧与转速无关。 */
    private static List<RotationSample> human(Random random) {
        List<RotationSample> samples = new ArrayList<>(FRAMES);
        float yaw = (float) (random.nextDouble() * 360.0);
        double sign = 1;
        for (int i = 0; i < FRAMES; i++) {
            if (random.nextDouble() < 0.12) {
                sign = -sign;
            }
            double step = (random.nextDouble() < 0.08)
                    ? 0.0
                    : sign * (3.0 + random.nextDouble() * 30.0);
            boolean attacking = random.nextDouble() < 0.45;
            samples.add(new RotationSample(yaw, 0f, i * 50L, attacking));
            yaw += (float) step;
        }
        return samples;
    }
}
