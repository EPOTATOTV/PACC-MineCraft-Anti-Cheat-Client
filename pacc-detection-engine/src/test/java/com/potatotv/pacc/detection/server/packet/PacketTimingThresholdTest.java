package com.potatotv.pacc.detection.server.packet;

import com.potatotv.pacc.detection.fusion.Evidence;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketTimingThresholdTest {

    @Test
    void jitteryStreamFlagsAtDefaultThreshold() {
        PacketTiming timing = new PacketTiming();
        record(timing, 40);
        Optional<Evidence> evidence = timing.checkTimer("p1", 50L);
        assertTrue(evidence.isPresent(), "平均间隔 40ms 已超过 1.005 的默认阈值");
    }

    @Test
    void widenedThresholdSuppressesJitterFalsePositive() {
        PacketTiming timing = new PacketTiming();
        record(timing, 40);
        // rate = 50/40 = 1.25；阈值放宽到 1.005×1.5≈1.5075 后不应再出证
        Optional<Evidence> evidence = timing.checkTimer("p1", 50L, 1.5);
        assertFalse(evidence.isPresent());
    }

    @Test
    void checkDelegatesWithThresholdScale() {
        PacketTiming timing = new PacketTiming();
        record(timing, 40);
        assertTrue(timing.check("p1", 50L, 1.0).isPresent());
        assertFalse(timing.check("p1", 50L, 2.0).isPresent());
    }

    @Test
    void thresholdScaleBelowOneIsIgnored() {
        PacketTiming timing = new PacketTiming();
        record(timing, 60);
        // rate = 50/60 ≈ 0.833。若把系数 0.5 直接乘上去（阈值 0.5025）会误判成 Timer；
        // 放宽系数只允许向上，所以这里必须不出证
        assertFalse(timing.checkTimer("p1", 50L, 0.5).isPresent());
    }

    /** 以固定间隔喂 21 个移动包，凑够 Timer 判定的最小样本数。 */
    private static void record(PacketTiming timing, long intervalMs) {
        for (int i = 0; i <= 21; i++) {
            timing.recordMovement("p1", 1000L + i * intervalMs);
        }
    }
}
