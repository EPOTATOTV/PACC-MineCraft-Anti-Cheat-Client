package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketIntelCheckTest {

    @Test
    void withoutTransactionSenderNothingIsAccounted() {
        IntelRuntime runtime = new IntelRuntime();
        PacketIntelCheck check = new PacketIntelCheck(runtime);
        for (int i = 0; i < 40; i++) {
            check.onPacket(movement(1000L + i * 50));
        }
        // 平台发不出事务包时不能记账：否则 ack 率恒为 0，正常玩家会被判成 Disabler
        assertFalse(runtime.transactionsSupported());
        assertEquals(0, runtime.latencyCompensator().sentCount());
        assertEquals(0, runtime.latencyCompensator().ackedCount());
    }

    @Test
    void transactionSenderIsUsedEveryTwentyMovementPackets() {
        IntelRuntime runtime = new IntelRuntime();
        AtomicInteger nextId = new AtomicInteger();
        runtime.attachTransactionSender(playerId -> nextId.incrementAndGet());
        PacketIntelCheck check = new PacketIntelCheck(runtime);
        for (int i = 0; i < 40; i++) {
            check.onPacket(movement(1000L + i * 50));
        }
        assertTrue(runtime.transactionsSupported());
        assertEquals(2, runtime.latencyCompensator().sentCount());
    }

    @Test
    void tickFeedsPerformanceCalibrator() {
        IntelRuntime runtime = new IntelRuntime();
        PacketIntelCheck check = new PacketIntelCheck(runtime);
        for (int i = 0; i <= 20; i++) {
            check.onTick(i * 100L);
        }
        assertEquals(10.0, runtime.calibrator().tps(), 0.2);
        assertTrue(runtime.calibrator().lowThroughput());
    }

    @Test
    void normalPacketFlowStaysClean() {
        IntelRuntime runtime = new IntelRuntime();
        PacketIntelCheck check = new PacketIntelCheck(runtime);
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 40; i++) {
            results = check.onPacket(movement(1000L + i * 50));
        }
        assertTrue(results.isEmpty(), "50ms 等间隔的正常移动流不应出证");
    }

    @Test
    void resetClearsPerPlayerState() {
        IntelRuntime runtime = new IntelRuntime();
        PacketIntelCheck check = new PacketIntelCheck(runtime);
        check.onPacket(movement(1000));
        check.reset("p1");
        assertTrue(check.onPacket(movement(1100)).isEmpty());
    }

    private static PacketView movement(long timestampMs) {
        return new PacketView("p1", "PLAYER_POSITION", timestampMs, Map.of(
                "x", "0.0", "y", "64.0", "z", "0.0", "onGround", "true"));
    }
}
