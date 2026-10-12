package com.potatotv.pacc.detection.server.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.profile.RotationSample;
import com.potatotv.pacc.detection.server.behavior.MLDetector;
import com.potatotv.pacc.detection.server.prediction.Position;

class CombatChecksTest {

    private static Evidence checked(Optional<Evidence> evidence) {
        assertTrue(evidence.isPresent(), "应产出证据");
        return evidence.get();
    }

    @Test
    void reachBoundaryAt301() {
        ReachCheck check = new ReachCheck();
        Position attacker = new Position(0, 64, 0);
        // 3.0 格：恰好在上限内，无证据
        assertTrue(check.check("a", attacker, new Position(0, 64, 3.0), true, 1L).isEmpty());
        // 3.02 格：越过 3.01 阈值，出证
        Evidence evidence = checked(check.check("a", attacker, new Position(0, 64, 3.02), true, 2L));
        assertEquals("combat-reach", evidence.detectorId());
        assertEquals(Layer.BEHAVIOR, evidence.layer());
        assertTrue(evidence.confidence() > 0.6 && evidence.confidence() <= 1.0);
        assertTrue(evidence.detail().contains("reach=3.02"));
        // 连续违规放大置信
        Evidence second = checked(check.check("a", attacker, new Position(0, 64, 3.02), true, 3L));
        assertTrue(second.confidence() > evidence.confidence());
        // 超过 6 格视为传送数据，不出证且中断连续链
        assertTrue(check.check("a", attacker, new Position(0, 64, 7.0), true, 4L).isEmpty());
        Evidence afterTeleport = checked(check.check("a", attacker, new Position(0, 64, 3.02), true, 5L));
        assertEquals(evidence.confidence(), afterTeleport.confidence(), 1e-12);
        Evidence fresh = checked(check.check("b", attacker, new Position(0, 64, 3.5), false, 6L));
        assertTrue(fresh.confidence() > 0.6);
        check.reset("b");
    }

    @Test
    void rotationAnalyzerFlagsMachineLikeWindow() {
        RotationAnalyzer analyzer = new RotationAnalyzer();
        // 匀速 500°/s、单步 50°（瞬间锁定）、无回调 → 机器似然 = 0.35 + 0.40 + 0.25 = 1.0
        for (int i = 0; i < 12; i++) {
            analyzer.record("p", new RotationSample(i * 50f, 0f, i * 100L, true));
        }
        Evidence evidence = checked(analyzer.check("p"));
        assertEquals("combat-rotation", evidence.detectorId());
        assertTrue(evidence.confidence() >= 0.6);
        assertTrue(evidence.detail().contains("机器旋转似然"));
    }

    @Test
    void rotationAnalyzerIgnoresHumanJitter() {
        RotationAnalyzer analyzer = new RotationAnalyzer();
        // 抖动 + 频繁反转：人类特征
        float[] deltas = {10f, -8f, 12f, -9f};
        float yaw = 0f;
        for (int i = 0; i < 25; i++) {
            yaw += deltas[i % deltas.length];
            analyzer.record("p", new RotationSample(yaw, 0f, i * 100L, true));
        }
        assertTrue(analyzer.check("p").isEmpty());
        // 样本不足不下结论
        RotationAnalyzer sparse = new RotationAnalyzer();
        for (int i = 0; i < 5; i++) {
            sparse.record("p", new RotationSample(i * 50f, 0f, i * 100L, true));
        }
        assertTrue(sparse.check("p").isEmpty());
    }

    @Test
    void knockbackDeviationTriggersOnSecondConsecutiveRefusal() {
        KnockbackPredictor predictor = new KnockbackPredictor();
        Position before = new Position(0, 64, 0);
        // strength 4 → 预测位移幅值 = sqrt(1.6² + 0.52²) ≈ 1.682
        predictor.recordKnockback("p", before, 4.0, 0L);
        // 正常吃满击退（沿 x 位移幅值同预测）→ 偏差 ~0，清零
        Position taken = new Position(1.682379359, 64, 0);
        assertTrue(predictor.check("p", taken, true).isEmpty());
        // 完全没动（免疫击退）第 1 次：只记 strike
        predictor.recordKnockback("p", before, 4.0, 10L);
        assertTrue(predictor.check("p", before, true).isEmpty());
        // 第 2 次连续偏差 → 出证 0.9
        predictor.recordKnockback("p", before, 4.0, 20L);
        Evidence evidence = checked(predictor.check("p", before, true));
        assertEquals("combat-knockback", evidence.detectorId());
        assertEquals(0.9, evidence.confidence());
        assertTrue(evidence.detail().contains("AntiKnockback"));
        // 非击退窗口与无记录直接跳过
        assertTrue(predictor.check("p", before, false).isEmpty());
        assertTrue(predictor.check("q", before, true).isEmpty());
    }

    @Test
    void attackPatternGcdKillAura() {
        // 固定 100ms 间隔：两两 GCD = 100 > 20，占比 1.0
        assertEquals(1.0, AttackPattern.gcdPatternRatio(new long[] {100, 100, 100}));
        assertEquals(0.0, AttackPattern.gcdPatternRatio(new long[] {100, 37}));
        AttackPattern pattern = new AttackPattern();
        for (int i = 0; i < 12; i++) {
            pattern.recordAttack("p", i * 100L, "victim");
        }
        Evidence evidence = checked(pattern.check("p"));
        assertEquals("combat-attack-pattern", evidence.detectorId());
        assertEquals(0.8, evidence.confidence());
        assertTrue(evidence.detail().contains("KillAura"));
    }

    @Test
    void attackPatternCyclicMultiAura() {
        AttackPattern pattern = new AttackPattern();
        // 目标 A,B,C,D 严格轮换，间隔带抖动（不触发 KillAura 分支）
        long[] times = {0, 80, 220, 350, 470, 610, 730, 860, 990, 1130, 1240, 1390};
        String[] targets = {"a", "b", "c", "d", "a", "b", "c", "d", "a", "b", "c", "d"};
        for (int i = 0; i < times.length; i++) {
            pattern.recordAttack("p", times[i], targets[i]);
        }
        Evidence evidence = checked(pattern.check("p"));
        assertEquals(0.7, evidence.confidence());
        assertTrue(evidence.detail().contains("MultiAura"));
        assertTrue(evidence.detail().contains("目标数=4"));
    }

    @Test
    void attackPatternHumanJitterIsClean() {
        AttackPattern pattern = new AttackPattern();
        // 单目标、间隔大抖动
        long[] times = {0, 180, 260, 470, 520, 760, 890, 940, 1230, 1310, 1520, 1590};
        for (long time : times) {
            pattern.recordAttack("p", time, "victim");
        }
        assertTrue(pattern.check("p").isEmpty());
        pattern.reset("p");
        assertTrue(pattern.check("p").isEmpty());
    }

    @Test
    void hitboxRejectsImpossibleRays() {
        HitboxValidator validator = new HitboxValidator();
        Position eye = new Position(0, 70, 0);
        float yaw = 0f; // 视线朝 +Z
        double width = 0.6;
        double height = 1.8;
        // 受击方正前方、命中箱与视线相交 → 无证据
        assertTrue(validator.check("att", 1.62, eye, yaw, new Position(0, 69, 5), width, height).isEmpty());
        // 受击方在侧向 3 格外：射线到命中箱最近距离 2.7 > 0.3·√2+0.1 → 不可能命中
        Evidence side = checked(validator.check("att", 1.62, eye, yaw, new Position(3, 69, 5), width, height));
        assertEquals("combat-hitbox", side.detectorId());
        assertEquals(0.85, side.confidence());
        // 受击方在背后
        Evidence behind = checked(validator.check("att", 1.62, eye, yaw, new Position(0, 69, -5), width, height));
        assertEquals(0.85, behind.confidence());
        assertTrue(behind.detail().contains("不可能命中"));
    }

    @Test
    void rotationFeatureHelpersKeepDimensions() {
        List<RotationSample> samples = List.of(
                new RotationSample(0f, 0f, 0L, false),
                new RotationSample(45f, 10f, 100L, true));
        List<double[]> features = MLDetector.rotationFeatures(samples);
        assertEquals(2, features.size());
        for (double[] feature : features) {
            assertEquals(3, feature.length);
        }
        assertEquals(4.5, features.get(1)[0], 1e-9);
        assertEquals(1.0, features.get(1)[1], 1e-9);
        assertEquals(0.25, features.get(1)[2], 1e-9);
    }
}
