package com.potatotv.pacc.detection.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AimProfile} 旋转画像测试：完美平滑（匀速、无过冲）应给高机器 likelihood，
 * 人类噪声样本（方向反复、速度起伏）应给低机器 likelihood。
 */
class AimProfileTest {

    private static final double DELTA = 1e-6;

    @Test
    void tooFewSamplesYieldsEmptyProfile() {
        List<RotationSample> few = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            few.add(new RotationSample(i * 30f, 0f, i * 50L, true));
        }
        assertEquals(AimProfile.empty(), AimProfile.fromRotations(few));
        assertEquals(0.0, AimProfile.empty().machineLikelihood(), DELTA);
    }

    @Test
    void smoothUniformRotationIsMachineLike() {
        AimProfile profile = AimProfile.fromRotations(smoothRotation(21, 30f));
        assertEquals(0.0, profile.yawRateVariance(), DELTA, "匀速旋转角速度方差趋零");
        assertEquals(0.0, profile.snapRatio(), DELTA, "小步平滑转动不产生 snap");
        assertEquals(0.0, profile.overshootRatio(), DELTA, "单调逼近无过冲");
        assertTrue(profile.machineLikelihood() > 0.5, "完美平滑应给较高机器 likelihood");
    }

    @Test
    void noisyHumanRotationLooksLessMechanical() {
        AimProfile machine = AimProfile.fromRotations(smoothRotation(21, 30f));
        AimProfile human = AimProfile.fromRotations(noisyHumanRotation());
        assertTrue(human.yawRateVariance() > machine.yawRateVariance(), "人类旋转角速度起伏应更大");
        assertTrue(human.overshootRatio() > machine.overshootRatio(), "人类瞄准存在过冲回调");
        assertTrue(machine.machineLikelihood() > human.machineLikelihood(),
                "机械样本 likelihood 应高于人类样本");
    }

    @Test
    void yawRateIsZeroForNonPositiveDeltaTime() {
        RotationSample a = new RotationSample(0f, 0f, 1000L, false);
        RotationSample b = new RotationSample(90f, 0f, 1000L, false);
        assertEquals(0.0, RotationSample.yawRate(a, b), DELTA, "时间差为 0 时角速度应为 0");
    }

    @Test
    void mlDisguiseRaisesLikelihoodWithoutPassingBaseThreshold() {
        AimProfile disguised = AimProfile.fromRotations(mlDisguisedRotation());
        assertTrue(disguised.machineLikelihood() < 0.6,
                "基础启发式应被伪装压低，不能直接越过出证阈值");
        assertTrue(disguised.mlDisguiseLikelihood() >= 0.65,
                "旋转-CPS 相关 + 低 jerk + 长窗口周期应给出高 ML 伪装似然");
        assertTrue(Math.abs(disguised.rotationCpsCorrelation()) > 0.5,
                "攻击帧应与高角速度同拍");
    }

    @Test
    void humanNoiseDoesNotLookLikeMlDisguise() {
        AimProfile human = AimProfile.fromRotations(noisyHumanRotation());
        assertTrue(human.mlDisguiseLikelihood() < 0.5,
                "无规律的左右修正不应触发 ML 伪装分量");
    }

    @Test
    void normalizeDeltaWrapsIntoHalfOpenRange() {
        assertEquals(-10.0, RotationSample.normalizeDelta(350.0), DELTA);
        assertEquals(10.0, RotationSample.normalizeDelta(10.0), DELTA);
        assertEquals(-180.0, RotationSample.normalizeDelta(180.0), DELTA);
    }

    /** 匀速旋转：每 50ms 转动固定角度，全程攻击姿态。 */
    private static List<RotationSample> smoothRotation(int count, float stepDeg) {
        List<RotationSample> samples = new ArrayList<>();
        float yaw = 0f;
        for (int i = 0; i < count; i++) {
            samples.add(new RotationSample(yaw, 0f, i * 50L, true));
            yaw += stepDeg;
        }
        return samples;
    }

    /** 人类噪声旋转：方向反复、步长不均（均小于 snap 阈值 40 度）。 */
    private static List<RotationSample> noisyHumanRotation() {
        float[] deltas = {25, -30, 20, -35, 28, -22, 33, -26, 24, -31,
                27, -29, 23, -34, 21, -32, 26, -28, 30, -25};
        List<RotationSample> samples = new ArrayList<>();
        float yaw = 0f;
        for (int i = 0; i <= deltas.length; i++) {
            samples.add(new RotationSample(yaw, 0f, i * 50L, true));
            if (i < deltas.length) {
                yaw += deltas[i];
            }
        }
        return samples;
    }

    /**
     * ML 转头伪装：角速度按固定节拍起伏（低二阶差分 + 强自相关），
     * 且只在高角速度帧出手（旋转与攻击同拍）。方向不反转，基础启发式拿不到 snap / 过冲分。
     */
    private static List<RotationSample> mlDisguisedRotation() {
        int count = 120;
        List<RotationSample> samples = new ArrayList<>();
        float yaw = 0f;
        for (int i = 0; i < count; i++) {
            double phase = 2 * Math.PI * i / 20.0;
            float step = (float) (10.0 + 25.0 * Math.sin(phase));
            boolean attacking = step > 10f;
            samples.add(new RotationSample(yaw, 0f, i * 50L, attacking));
            yaw += step;
        }
        return samples;
    }
}
