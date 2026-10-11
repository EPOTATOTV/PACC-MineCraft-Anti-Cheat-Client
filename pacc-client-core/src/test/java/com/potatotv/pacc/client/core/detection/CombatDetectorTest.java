package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientDetectionResult;
import com.potatotv.pacc.client.core.model.CombatSample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDetectorTest {

    private static CombatSample attack(String target, double targetX, double angle, long ts) {
        return new CombatSample(target, 0, 0, 0, targetX, 0, 0, angle, ts);
    }

    @Test
    void detectsReach() {
        CombatDetector detector = new CombatDetector();
        List<ClientDetectionResult> results = detector.onAttack(attack("t1", 10, 0, 0));
        assertTrue(results.stream().anyMatch(r -> "REACH".equals(r.cheatType())));
    }

    @Test
    void cleanAttackProducesNoViolation() {
        CombatDetector detector = new CombatDetector();
        List<ClientDetectionResult> results = detector.onAttack(attack("t1", 2, 0, 0));
        assertTrue(results.isEmpty());
    }

    @Test
    void detectsAutoClicker() {
        CombatDetector detector = new CombatDetector(100.0, 2);
        long ts = 0;
        List<ClientDetectionResult> results = List.of();
        for (int i = 0; i < 3; i++) {
            results = detector.onAttack(attack("t1", 1, 0, ts));
            ts += 100;
        }
        assertTrue(results.stream().anyMatch(r -> "AUTO_CLICKER".equals(r.cheatType())));
    }

    @Test
    void detectsKillAuraTargetSwitch() {
        CombatDetector detector = new CombatDetector(100.0, 100);
        String[] targets = {"a", "b", "c", "d", "e", "f"};
        long ts = 0;
        List<ClientDetectionResult> results = List.of();
        for (String target : targets) {
            results = detector.onAttack(attack(target, 1, 0, ts));
            ts += 50;
        }
        assertTrue(results.stream().anyMatch(r -> "KILL_AURA".equals(r.cheatType())
                && r.detail().contains("切换目标")));
    }

    @Test
    void detectsOutOfViewHit() {
        CombatDetector detector = new CombatDetector(100.0, 100);
        List<ClientDetectionResult> results = detector.onAttack(attack("t1", 1, 120, 0));
        assertTrue(results.stream().anyMatch(r -> "KILL_AURA".equals(r.cheatType())
                && r.detail().contains("视角外")));
    }

    @Test
    void nullInputIsSafe() {
        assertTrue(new CombatDetector().onAttack(null).isEmpty());
    }
}
