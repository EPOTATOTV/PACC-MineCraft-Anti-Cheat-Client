package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.DataConsistencyCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DataConsistencyCheckTest {

    private final DataConsistencyCheck check = new DataConsistencyCheck();

    @Test
    void matchingValuesAreClean() {
        assertTrue(check.onDataConsistency("p1", Map.of("tps", "20"), Map.of("tps", "20")).isEmpty());
    }

    @Test
    void deviationOverThresholdIsFlagged() {
        List<DetectionResult> results = check.onDataConsistency("p2", Map.of("speed", "2"), Map.of("speed", "1"));
        assertTrue(hasCheat(results, "DATA_MISMATCH"));
    }

    @Test
    void deviationWithinThresholdIsClean() {
        assertTrue(check.onDataConsistency("p3", Map.of("v", "1.1"), Map.of("v", "1.0")).isEmpty());
    }

    @Test
    void nonNumericAndUnknownKeysAreClean() {
        assertTrue(check.onDataConsistency("p4", Map.of("a", "x"), Map.of("a", "y")).isEmpty());
        assertTrue(check.onDataConsistency("p5", Map.of("a", "1"), Map.of("b", "1")).isEmpty());
    }

    @Test
    void emptyOrNullInputIsClean() {
        assertTrue(check.onDataConsistency("p6", Map.of(), Map.of("a", "1")).isEmpty());
        assertTrue(check.onDataConsistency("p6", null, Map.of("a", "1")).isEmpty());
        assertTrue(check.onDataConsistency("p6", Map.of("a", "1"), null).isEmpty());
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(
                r -> r.checkType() == CheckType.DATA_CONSISTENCY && cheatType.equals(r.cheatType()));
    }
}
