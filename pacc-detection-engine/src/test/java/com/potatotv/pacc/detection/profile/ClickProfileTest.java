package com.potatotv.pacc.detection.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClickProfile} 点击分布画像测试：机械样本（方差趋零、间隔恒定）应被判 robotic，
 * 人类样本（间隔散布、熵高）不应被误判。
 */
class ClickProfileTest {

    private static final double DELTA = 1e-6;

    @Test
    void tooFewClicksYieldsEmptyProfile() {
        ClickProfile profile = ClickProfile.fromClickTimestamps(List.of(0L, 100L, 200L));
        assertEquals(ClickProfile.empty(), profile);
        assertFalse(profile.robotic());
        assertEquals(0.0, profile.machineLikelihood(), DELTA);
    }

    @Test
    void mechanicalConstantIntervalsAreRobotic() {
        // 恒定 100ms 间隔：方差与熵趋零，两两 GCD 恒为 100ms
        ClickProfile profile = ClickProfile.fromClickTimestamps(timestamps(30, 100L));
        assertEquals(31, profile.clickCount());
        assertEquals(0.0, profile.intervalVarianceSeconds(), DELTA);
        assertEquals(0.0, profile.entropyBits(), DELTA);
        assertEquals(1.0, profile.gcdPatternRatio(), DELTA);
        assertTrue(profile.robotic(), "恒定间隔应判 robotic");
        assertTrue(profile.gcdPattern(), "间隔为固定毫秒倍数应判 gcdPattern");
        assertTrue(profile.machineLikelihood() > 0.9, "机械样本机器程度应接近 1");
    }

    @Test
    void humanVariableIntervalsAreNotRobotic() {
        // 15 个等频 50ms 桶均匀分布：熵约 3.9 bit（>=3.0），不足以判 robotic
        ClickProfile profile = ClickProfile.fromClickTimestamps(humanTimestamps());
        assertFalse(profile.robotic(), "间隔分布宽、熵高的样本不应被误判为机械");
        assertTrue(profile.entropyBits() >= 3.0, "人类样本熵应高于 3.0，实际 " + profile.entropyBits());
        assertFalse(profile.gcdPattern(), "人类间隔不呈固定倍数关系");
    }

    @Test
    void mechanicalSampleScoresHigherThanHuman() {
        ClickProfile machine = ClickProfile.fromClickTimestamps(timestamps(30, 100L));
        ClickProfile human = ClickProfile.fromClickTimestamps(humanTimestamps());
        assertTrue(machine.machineLikelihood() > human.machineLikelihood(),
                "机械样本机器程度应高于人类样本");
    }

    @Test
    void emptyProfileFactoryIsClean() {
        ClickProfile empty = ClickProfile.empty();
        assertEquals(0, empty.clickCount());
        assertFalse(empty.robotic());
        assertFalse(empty.gcdPattern());
        assertEquals(0.0, empty.machineLikelihood(), DELTA);
    }

    /** 生成固定间隔的点击时间戳序列。 */
    private static List<Long> timestamps(int intervalCount, long interval) {
        List<Long> ts = new ArrayList<>();
        long t = 0;
        ts.add(t);
        for (int i = 0; i < intervalCount; i++) {
            t += interval;
            ts.add(t);
        }
        return ts;
    }

    /** 人类样本：15 个 50ms 桶各出现两次，间隔散布、熵高。 */
    private static List<Long> humanTimestamps() {
        long[] cycle = {55, 105, 155, 205, 255, 305, 355, 405, 455, 505, 555, 605, 655, 705, 755};
        List<Long> ts = new ArrayList<>();
        long t = 0;
        ts.add(t);
        for (int round = 0; round < 2; round++) {
            for (long interval : cycle) {
                t += interval;
                ts.add(t);
            }
        }
        return ts;
    }
}
