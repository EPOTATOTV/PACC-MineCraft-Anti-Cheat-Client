package com.potatotv.pacc.detection.server.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.server.prediction.LatencyCompensator;

class PacketChecksTest {

    private static Evidence checked(Optional<Evidence> evidence) {
        assertTrue(evidence.isPresent(), "应产出证据");
        return evidence.get();
    }

    @Test
    void timerFlagsFasterThanTickRate() {
        PacketTiming timing = new PacketTiming();
        // 40ms 一包：rate = 50/40 = 1.25 > 1.005
        for (int i = 0; i < 40; i++) {
            timing.recordMovement("p", i * 40L);
        }
        Evidence timer = checked(timing.checkTimer("p", 50L));
        assertEquals("packet-timing", timer.detectorId());
        assertEquals(Layer.NETWORK, timer.layer());
        assertEquals(0.745, timer.confidence(), 1e-9);
        assertTrue(timer.detail().contains("rate=1.2500"));
        // 50ms 一包：恰好 tick 速率，不出证
        PacketTiming normal = new PacketTiming();
        for (int i = 0; i < 40; i++) {
            normal.recordMovement("p", i * 50L);
        }
        assertTrue(normal.check("p", 50L).isEmpty());
        // 样本不足不下结论
        PacketTiming sparse = new PacketTiming();
        for (int i = 0; i < 5; i++) {
            sparse.recordMovement("p", i * 40L);
        }
        assertTrue(sparse.check("p", 50L).isEmpty());
    }

    @Test
    void blinkFlagsGapBurst() {
        PacketTiming timing = new PacketTiming();
        // 25 包 × 50ms，突然 3000ms 静默，再恢复 50ms
        for (int i = 0; i < 25; i++) {
            timing.recordMovement("p", i * 50L);
        }
        timing.recordMovement("p", 1200L + 3000L);
        timing.recordMovement("p", 1200L + 3050L);
        timing.recordMovement("p", 1200L + 3100L);
        // Timer 分支不触发（平均间隔被拉大），Blink 出证 0.5
        Evidence evidence = checked(timing.check("p", 50L));
        assertEquals(0.5, evidence.confidence());
        assertTrue(evidence.detail().contains("Blink"));
    }

    @Test
    void packetOrderFlagsSequenceRegression() {
        PacketOrder order = new PacketOrder();
        assertTrue(order.check("p", "MOVE", 5).isEmpty());
        assertTrue(order.check("p", "MOVE", 7).isEmpty());
        Evidence regression = checked(order.check("p", "MOVE", 6));
        assertEquals("packet-order", regression.detectorId());
        assertEquals(0.6, regression.confidence());
        assertTrue(regression.detail().contains("last=7"));
        assertTrue(regression.detail().contains("current=6"));
        // 保留历史最大值：继续倒退仍出证
        assertTrue(order.check("p", "MOVE", 6).isPresent());
        // 其他类型互不影响
        assertTrue(order.check("p", "USE", 1).isEmpty());
        order.reset("p");
        assertTrue(order.check("p", "MOVE", 1).isEmpty());
    }

    @Test
    void transactionSyncFlagsLowAckRate() {
        TransactionSync sync = new TransactionSync();
        LatencyCompensator compensator = new LatencyCompensator();
        long base = System.currentTimeMillis();
        int[] ids = new int[12];
        for (int i = 0; i < 12; i++) {
            ids[i] = compensator.sendTransaction();
        }
        for (int i = 0; i < 5; i++) {
            compensator.ack(ids[i], base + 50);
        }
        Evidence disabler = checked(sync.check("p", compensator));
        assertEquals("packet-transaction", disabler.detectorId());
        assertEquals(0.85, disabler.confidence());
        assertTrue(disabler.detail().contains("Disabler"));
    }

    @Test
    void transactionSyncFlagsPingSpoofAndHealthyPass() {
        TransactionSync sync = new TransactionSync();
        // 高 ack 率 + 正常延迟 → 干净
        LatencyCompensator healthy = new LatencyCompensator();
        long base = System.currentTimeMillis();
        int[] ids = new int[12];
        for (int i = 0; i < 12; i++) {
            ids[i] = healthy.sendTransaction();
        }
        for (int id : ids) {
            healthy.ack(id, base + 50);
        }
        assertTrue(sync.check("p", healthy).isEmpty());
        // 事务少但有 ack 且延迟 > 10s → PingSpoof
        LatencyCompensator spoofer = new LatencyCompensator();
        int id = spoofer.sendTransaction();
        spoofer.ack(id, base + 11_000);
        Evidence spoof = checked(sync.check("p", spoofer));
        assertEquals(0.6, spoof.confidence());
        assertTrue(spoof.detail().contains("PingSpoof"));
        assertFalse(sync.check("p", null).isPresent());
    }

    @Test
    void brandValidatorChecksBrandsAndMods() {
        BrandValidator validator = new BrandValidator();
        assertEquals(0.3, checked(validator.check("p", null, List.of())).confidence());
        assertEquals(0.3, checked(validator.check("p", "  ", List.of())).confidence());
        Evidence wurst = checked(validator.check("p", "wurst client 7.0", List.of()));
        assertEquals("packet-brand", wurst.detectorId());
        assertEquals(0.95, wurst.confidence());
        assertEquals(Layer.NETWORK, wurst.layer());
        Evidence mismatch = checked(validator.check("p", "vanilla", List.of("sodium", "lithium")));
        assertEquals(0.5, mismatch.confidence());
        assertTrue(mismatch.detail().contains("不一致"));
        // 声称 vanilla 且确无 Mod；或 forge 品牌正常 → 干净
        assertTrue(validator.check("p", "vanilla", List.of()).isEmpty());
        assertTrue(validator.check("p", "fml,forge", List.of("sodium")).isEmpty());
        assertTrue(validator.check("p", "fml,forge", null).isEmpty());
    }
}
