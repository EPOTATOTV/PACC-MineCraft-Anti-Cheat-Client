package com.potatotv.pacc.detection.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BehaviorProfile} 会话画像测试：聚合点击/旋转窗口并维护会话级统计。
 */
class BehaviorProfileTest {

    private static final double DELTA = 1e-9;

    @Test
    void emptyProfileReportsZeroRatios() {
        BehaviorProfile profile = new BehaviorProfile();
        assertEquals(0.0, profile.roboticClickRatio(), DELTA);
        assertEquals(0.0, profile.maxAimMachineLikelihood(), DELTA);
        assertEquals(0L, profile.startedAtMs());
        assertEquals(0L, profile.lastUpdateMs());
    }

    @Test
    void roboticClickRatioIsShareOfMechanicalWindows() {
        BehaviorProfile profile = new BehaviorProfile();
        profile.addClickWindow(roboticClick());
        profile.addClickWindow(humanClick());
        assertEquals(0.5, profile.roboticClickRatio(), DELTA);
    }

    @Test
    void maxAimMachineLikelihoodKeepsHighest() {
        BehaviorProfile profile = new BehaviorProfile();
        profile.addAimWindow(new AimProfile(10, 600, 0, 0, 0, 0.6));
        profile.addAimWindow(new AimProfile(10, 500, 100, 1, 0.9, 0.4));
        assertEquals(0.6, profile.maxAimMachineLikelihood(), DELTA);
    }

    @Test
    void emptyWindowsAreIgnored() {
        BehaviorProfile profile = new BehaviorProfile();
        profile.addClickWindow(null);
        profile.addClickWindow(ClickProfile.empty());
        profile.addAimWindow(null);
        profile.addAimWindow(AimProfile.empty());
        assertTrue(profile.clickWindows().isEmpty());
        assertTrue(profile.aimWindows().isEmpty());
        assertEquals(0.0, profile.roboticClickRatio(), DELTA);
    }

    @Test
    void sessionTimestampsAdvanceOnUpdate() {
        BehaviorProfile profile = new BehaviorProfile();
        profile.addClickWindow(humanClick());
        assertTrue(profile.startedAtMs() > 0);
        assertTrue(profile.lastUpdateMs() >= profile.startedAtMs());
    }

    @Test
    void windowBuffersAreCappedAtCapacity() {
        BehaviorProfile profile = new BehaviorProfile();
        for (int i = 0; i < 130; i++) {
            profile.addClickWindow(humanClick());
            profile.addAimWindow(new AimProfile(10, 100, 1, 0, 0.5, 0.2));
        }
        assertEquals(120, profile.clickWindows().size(), "点击窗口超出容量应淘汰最旧");
        assertEquals(120, profile.aimWindows().size(), "旋转窗口超出容量应淘汰最旧");
    }

    private static ClickProfile roboticClick() {
        return new ClickProfile(20, 100.0, 0.0, 0.0, 1.0, true, true);
    }

    private static ClickProfile humanClick() {
        return new ClickProfile(20, 250.0, 0.4, 4.2, 0.1, false, false);
    }
}
