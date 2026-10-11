package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientCheckType;
import com.potatotv.pacc.client.core.model.ClientDetectionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModListScannerTest {

    private final ModListScanner scanner = new ModListScanner();

    @Test
    void detectsKnownCheatClients() {
        for (String id : List.of("wurst", "Impact", "meteor-client-1.0.jar", "Future", "rusherhack-2.0")) {
            assertTrue(scanner.isKnownCheat(id), "应命中: " + id);
        }
    }

    @Test
    void matchingIsCaseInsensitiveAndSubstring() {
        assertTrue(scanner.isKnownCheat("Wurst-Client"));
        assertTrue(scanner.isKnownCheat("com.example.liquidbounce-mod"));
        assertEquals("wurst", scanner.match("WURSTClient"));
    }

    @Test
    void cleanModsAreNotFlagged() {
        for (String id : List.of("sodium", "lithium", "iris", "fabric-api", "jei")) {
            assertFalse(scanner.isKnownCheat(id), "不应命中: " + id);
        }
    }

    @Test
    void scanReturnsViolationWithCheatType() {
        List<ClientDetectionResult> results = scanner.scan(List.of("sodium", "wurst"), 1234L);
        assertEquals(1, results.size());
        ClientDetectionResult result = results.get(0);
        assertEquals(ClientCheckType.MOD_LIST, result.checkType());
        assertEquals(ModListScanner.CHEAT_TYPE, result.cheatType());
        assertEquals(1234L, result.timestampMs());
        assertTrue(result.confidence() > 0);
    }

    @Test
    void scanHandlesEmptyAndNullEntries() {
        assertTrue(scanner.scan(null).isEmpty());
        assertTrue(scanner.scan(List.of()).isEmpty());
        assertTrue(scanner.scan(java.util.Arrays.asList("sodium", null, " ")).isEmpty());
    }

    @Test
    void signaturesAreBundledAsLowercase() {
        assertNotNull(scanner.signatures());
        assertFalse(scanner.signatures().isEmpty());
        for (String signature : scanner.signatures()) {
            assertEquals(signature.toLowerCase(java.util.Locale.ROOT), signature);
        }
    }
}
