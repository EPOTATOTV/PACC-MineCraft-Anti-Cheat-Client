package com.potatotv.pacc.detection.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MovementProfile} 移动基线测试：冷启动不误报、偏离 3σ 判离群、滚动窗口容量受限。
 */
class MovementProfileTest {

    private static final double DELTA = 1e-6;

    @Test
    void coldStartDoesNotFlagOutlier() {
        MovementProfile profile = new MovementProfile();
        for (int i = 0; i < 10; i++) {
            profile.addSpeed(0.2);
        }
        assertEquals(10, profile.sampleCount());
        assertEquals(0.0, profile.zScore(100.0), DELTA, "样本不足 32 时基线未成型，z 分数恒为 0");
        assertFalse(profile.outlier(100.0));
    }

    @Test
    void outlierBeyondThreeSigmaIsFlagged() {
        MovementProfile profile = new MovementProfile();
        // 交替 1.0 / 3.0：均值 2.0、标准差 1.0（整数取值避免浮点边界误差）
        for (int i = 0; i < 40; i++) {
            profile.addSpeed(i % 2 == 0 ? 1.0 : 3.0);
        }
        assertEquals(2.0, profile.mean(), 1e-9);
        assertEquals(1.0, profile.std(), 1e-9);
        assertEquals(4.0, profile.zScore(6.0), 1e-6, "6.0 相对基线偏离 4σ");
        assertTrue(profile.outlier(6.0), "4σ 应判离群");
        assertFalse(profile.outlier(3.0), "基线内速度不应判离群");
        assertFalse(profile.outlier(5.0), "恰好 3σ 不算超过阈值");
    }

    @Test
    void rollingWindowKeepsLatestSamples() {
        MovementProfile profile = new MovementProfile();
        for (int i = 0; i < MovementProfile.WINDOW_SIZE + 100; i++) {
            profile.addSpeed(1.0);
        }
        assertEquals(MovementProfile.WINDOW_SIZE, profile.sampleCount(), "窗口应剔除最旧样本");
        assertEquals(1.0, profile.mean(), DELTA);
        assertEquals(0.0, profile.std(), DELTA);
    }

    @Test
    void constantBaselineUsesDirectionalThreshold() {
        MovementProfile profile = new MovementProfile();
        for (int i = 0; i < 40; i++) {
            profile.addSpeed(2.0);
        }
        // 标准差为 0 时，高于均值返回 σ 阈值本身（严格大于才算离群）
        assertEquals(MovementProfile.SIGMA_THRESHOLD, profile.zScore(2.5), DELTA);
        assertFalse(profile.outlier(2.5));
        assertEquals(0.0, profile.zScore(1.0), DELTA, "低于均值不产生正向离群");
    }
}
