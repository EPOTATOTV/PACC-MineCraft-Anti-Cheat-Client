package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.InventoryCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryCheckTest {

    private final InventoryCheck check = new InventoryCheck();

    @Test
    void actionWhileInventoryOpenIsFlagged() {
        assertTrue(hasCheat(check.onInventory("p1", "MOVE_WHILE_OPEN", 1000L), "INVENTORY_MOVE"));
        assertTrue(hasCheat(check.onInventory("p1", "ATTACK_WHILE_OPEN", 1000L), "INVENTORY_MOVE"));
    }

    @Test
    void tooFastEatingIsFlagged() {
        assertTrue(check.onInventory("p-eat", "FAST_EAT", 1000L).isEmpty());
        assertTrue(hasCheat(check.onInventory("p-eat", "FAST_EAT", 1200L), "FAST_EAT"));
    }

    @Test
    void spacedEatingIsClean() {
        assertTrue(check.onInventory("p-eat2", "FAST_EAT", 1000L).isEmpty());
        assertTrue(check.onInventory("p-eat2", "FAST_EAT", 2000L).isEmpty());
    }

    @Test
    void tooFastTotemSwapIsFlagged() {
        assertTrue(check.onInventory("p-totem", "TOTEM_SWAP", 1000L).isEmpty());
        assertTrue(hasCheat(check.onInventory("p-totem", "TOTEM_SWAP", 1300L), "AUTO_TOTEM"));
    }

    @Test
    void unknownActionAndNullAreClean() {
        assertTrue(check.onInventory("p1", "OPEN", 1000L).isEmpty());
        assertTrue(check.onInventory(null, "FAST_EAT", 1000L).isEmpty());
        assertTrue(check.onInventory("p1", null, 1000L).isEmpty());
    }

    @Test
    void resetClearsState() {
        assertTrue(check.onInventory("p-reset", "FAST_EAT", 1000L).isEmpty());
        check.reset("p-reset");
        assertTrue(check.onInventory("p-reset", "FAST_EAT", 1100L).isEmpty());
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(
                r -> r.checkType() == CheckType.INVENTORY && cheatType.equals(r.cheatType()));
    }
}
