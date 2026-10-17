package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.model.ServerType;
import com.potatotv.pacc.server.core.packet.PacketCapability;
import com.potatotv.pacc.server.core.packet.PacketCapabilityMatrix;
import com.potatotv.pacc.server.core.packet.PacketTypes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketCapabilityMatrixTest {

    @Test
    void bukkitWithPacketEventsCoversAllRealTypes() {
        PacketCapability capability =
                PacketCapabilityMatrix.forPlatform(ServerType.PAPER, true, true);
        assertTrue(capability.realPacketSource());
        assertTrue(capability.phaseCheckAvailable());
        assertEquals(PacketTypes.REAL_PACKET_SOURCE_TYPES.size(), capability.coveredRealTypeCount());
        assertTrue(capability.coversAllRealTypes());
        assertTrue(capability.describe().contains("packetevents"));
    }

    @Test
    void bukkitWithoutPacketEventsDeclaresDegradation() {
        PacketCapability capability =
                PacketCapabilityMatrix.forPlatform(ServerType.SPIGOT, false, true);
        assertFalse(capability.realPacketSource());
        assertEquals("bukkit-events", capability.source());
        assertEquals(0, capability.coveredRealTypeCount());
        assertFalse(capability.describe().isEmpty());
    }

    @Test
    void bedrockHasNoRealPacketSource() {
        for (ServerType type : List.of(ServerType.NUKKIT, ServerType.NUKKIT_MOT, ServerType.ALLAYMC)) {
            PacketCapability capability = PacketCapabilityMatrix.forPlatform(type, true, true);
            // 即便传 true 也不该声称有真实包源：基岩系没有 PacketEvents 等价物
            assertFalse(capability.realPacketSource(), type + " 不应声称有真实包源");
            assertEquals(0, capability.coveredRealTypeCount());
        }
    }

    @Test
    void geyserNeverSupportsBlockProbe() {
        PacketCapability capability = PacketCapabilityMatrix.forPlatform(ServerType.GEYSER, true, true);
        assertFalse(capability.phaseCheckAvailable());
        assertFalse(capability.realPacketSource());
    }

    @Test
    void nullPlatformFallsBackToLowestCapability() {
        PacketCapability capability = PacketCapabilityMatrix.forPlatform(null, true, true);
        assertFalse(capability.realPacketSource());
        assertFalse(capability.phaseCheckAvailable());
    }

    @Test
    void ceilingListsEveryPlatform() {
        List<PacketCapability> ceiling = PacketCapabilityMatrix.ceiling();
        assertEquals(ServerType.values().length, ceiling.size());
    }

    @Test
    void reportExplainsDegradationForEventDrivenPlatforms() {
        PacketCapability capability =
                PacketCapabilityMatrix.forPlatform(ServerType.NUKKIT, false, false);
        String report = PacketCapabilityMatrix.report(capability);
        assertTrue(report.contains("不是故障"));
        assertTrue(report.contains("包级检测"));
    }
}
