package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.PacketCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketCheckTest {

    private final PacketCheck check = new PacketCheck();

    @Test
    void nanCoordinateFlagsInvalidPacket() {
        List<DetectionResult> results = check.onPacket(
                packet("p-nan", "PLAYER_POSITION", 1000, Map.of("x", "NaN", "y", "64", "z", "0")));
        assertTrue(hasCheat(results, "INVALID_PACKET"));
    }

    @Test
    void singleTickTeleportFlagsFastMove() {
        check.onPacket(packet("p-tp", "PLAYER_POSITION", 1000, Map.of("x", "0", "y", "64", "z", "0")));
        List<DetectionResult> results = check.onPacket(
                packet("p-tp", "PLAYER_POSITION", 1050, Map.of("x", "20", "y", "64", "z", "0")));
        assertTrue(hasCheat(results, "FAST_MOVE"));
    }

    @Test
    void groundedDropFlagsNoFall() {
        List<DetectionResult> results = check.onPacket(
                packet("p-fall", "PLAYER_GROUND", 1000, Map.of("y", "50", "deltaY", "-10")));
        assertTrue(hasCheat(results, "NO_FALL"));
    }

    @Test
    void normalMovementIsClean() {
        check.onPacket(packet("p-clean", "PLAYER_POSITION", 1000, Map.of("x", "0", "y", "64", "z", "0")));
        List<DetectionResult> results = check.onPacket(
                packet("p-clean", "PLAYER_POSITION", 1050, Map.of("x", "0.3", "y", "64", "z", "0")));
        assertFalse(hasCheat(results, "FAST_MOVE"));
        assertTrue(results.isEmpty());
    }

    private static PacketView packet(String playerId, String type, long ts, Map<String, String> fields) {
        return new PacketView(playerId, type, ts, fields);
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(r -> r.checkType() == CheckType.PACKET && cheatType.equals(r.cheatType()));
    }
}
