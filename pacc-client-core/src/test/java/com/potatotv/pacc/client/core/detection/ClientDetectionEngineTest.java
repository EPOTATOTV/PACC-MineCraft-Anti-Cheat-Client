package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientDetectionEvent;
import com.potatotv.pacc.client.core.model.ClientDetectionResult;
import com.potatotv.pacc.client.core.model.ClientIdentity;
import com.potatotv.pacc.client.core.redscreen.KeyboardLockController;
import com.potatotv.pacc.client.core.redscreen.RedScreenAlert;
import com.potatotv.pacc.client.core.redscreen.RedScreenController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDetectionEngineTest {

    private static final ClientIdentity IDENTITY = new ClientIdentity("PTEID123456", "Tester", "6.0.0");

    private static final class RecordingRedScreen implements RedScreenController {
        int showCount;
        boolean active;

        @Override
        public void show(RedScreenAlert alert) {
            showCount++;
            active = true;
        }

        @Override
        public void hide() {
            active = false;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }

    private static final class RecordingLock implements KeyboardLockController {
        boolean locked;

        @Override
        public void lock() {
            locked = true;
        }

        @Override
        public void unlock() {
            locked = false;
        }

        @Override
        public boolean isLocked() {
            return locked;
        }
    }

    @Test
    void reportsAndTriggersRedScreenOnCheatMod() {
        List<ClientDetectionEvent> reported = new ArrayList<>();
        RecordingRedScreen redScreen = new RecordingRedScreen();
        RecordingLock lock = new RecordingLock();
        ClientDetectionEngine engine =
                new ClientDetectionEngine(IDENTITY, reported::add, redScreen, lock);

        List<ClientDetectionResult> results = engine.scanMods(List.of("wurst"));

        assertEquals(1, results.size());
        assertEquals(1, reported.size());
        assertEquals("PTEID123456", reported.get(0).pteid());
        assertEquals(1, redScreen.showCount);
        assertTrue(lock.locked);
    }

    @Test
    void cleanInputProducesNothing() {
        List<ClientDetectionEvent> reported = new ArrayList<>();
        ClientDetectionEngine engine = new ClientDetectionEngine(IDENTITY, reported::add, null, null);
        assertTrue(engine.scanMods(List.of("sodium")).isEmpty());
        assertTrue(reported.isEmpty());
    }

    @Test
    void reporterExceptionDoesNotBreakDetection() {
        ClientDetectionEngine engine = new ClientDetectionEngine(
                IDENTITY,
                event -> {
                    throw new IllegalStateException("boom");
                },
                null,
                null);
        assertEquals(1, engine.scanMods(List.of("impact")).size());
    }

    @Test
    void redScreenConfidenceGate() {
        assertTrue(ClientDetectionEngine.isRedScreenConfidence(95));
        assertTrue(!ClientDetectionEngine.isRedScreenConfidence(80));
    }
}
