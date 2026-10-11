package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientDetectionResult;
import com.potatotv.pacc.client.core.model.GameMode;
import com.potatotv.pacc.client.core.model.PlayerMovement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveDetectorTest {

    private static PlayerMovement move(double x, double y, boolean onGround, GameMode mode, long ts) {
        return new PlayerMovement(x, y, 0, onGround, mode, ts);
    }

    @Test
    void detectsHorizontalSpeed() {
        MoveDetector detector = new MoveDetector();
        List<ClientDetectionResult> results = detector.onMove(
                move(0, 64, true, GameMode.SURVIVAL, 0),
                move(10, 64, true, GameMode.SURVIVAL, 50));
        assertTrue(results.stream().anyMatch(r -> "SPEED".equals(r.cheatType())));
    }

    @Test
    void cleanMovementProducesNoViolation() {
        MoveDetector detector = new MoveDetector();
        List<ClientDetectionResult> results = detector.onMove(
                move(0, 64, true, GameMode.SURVIVAL, 0),
                move(0.3, 64, true, GameMode.SURVIVAL, 50));
        assertTrue(results.isEmpty());
    }

    @Test
    void skipsCreativeAndSpectator() {
        MoveDetector detector = new MoveDetector();
        assertTrue(detector.onMove(
                move(0, 64, true, GameMode.CREATIVE, 0),
                move(50, 128, false, GameMode.CREATIVE, 50)).isEmpty());
        assertTrue(detector.onMove(
                move(0, 64, true, GameMode.SPECTATOR, 0),
                move(50, 128, false, GameMode.SPECTATOR, 50)).isEmpty());
    }

    @Test
    void detectsAscendingFlight() {
        MoveDetector detector = new MoveDetector();
        List<ClientDetectionResult> results = detector.onMove(
                move(0, 64, false, GameMode.SURVIVAL, 0),
                move(0, 66, false, GameMode.SURVIVAL, 50));
        assertTrue(results.stream().anyMatch(r -> "FLY".equals(r.cheatType())));
    }

    @Test
    void detectsSustainedAscendingAirborne() {
        // 抬高垂直阈值的检测器，只让「连续滞空且上升」命中，隔离出滞空计数器。
        MoveDetector detector = new MoveDetector(100.0, 100.0, 3);
        long ts = 0;
        PlayerMovement previous = move(0, 0, false, GameMode.SURVIVAL, ts);
        List<ClientDetectionResult> results = List.of();
        for (int i = 1; i <= 3; i++) {
            ts += 50;
            PlayerMovement current = move(0, i, false, GameMode.SURVIVAL, ts);
            results = detector.onMove(previous, current);
            previous = current;
        }
        assertTrue(results.stream().anyMatch(r -> "FLY".equals(r.cheatType())
                && r.detail().contains("连续滞空")));
    }

    @Test
    void resetClearsAirborneCounter() {
        MoveDetector detector = new MoveDetector(100.0, 100.0, 2);
        long ts = 0;
        PlayerMovement previous = move(0, 0, false, GameMode.SURVIVAL, ts);
        ts += 50;
        detector.onMove(previous, move(0, 1, false, GameMode.SURVIVAL, ts));
        detector.reset();
        ts += 50;
        List<ClientDetectionResult> results =
                detector.onMove(move(0, 1, false, GameMode.SURVIVAL, ts - 50), move(0, 2, false, GameMode.SURVIVAL, ts));
        assertFalse(results.stream().anyMatch(r -> r.detail().contains("连续滞空")));
    }

    @Test
    void nullInputIsSafe() {
        assertEquals(0, new MoveDetector().onMove(null, null).size());
    }
}
