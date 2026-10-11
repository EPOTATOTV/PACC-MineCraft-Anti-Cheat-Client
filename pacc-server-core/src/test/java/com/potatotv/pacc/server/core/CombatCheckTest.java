package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.CombatCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatCheckTest {

    private final ServerPluginConfig config = ServerPluginConfig.defaults();
    private final CombatCheck check = new CombatCheck(config);

    @Test
    void reachBeyondLimitFlagsReach() {
        List<DetectionResult> results = check.onCombat(combat("a-reach", "t", 5.0, 1000, 0));
        assertTrue(hasCheat(results, "REACH"));
    }

    @Test
    void normalAttackIsClean() {
        List<DetectionResult> results = check.onCombat(combat("a-clean", "t", 1.0, 1000, 0));
        assertTrue(results.isEmpty());
    }

    @Test
    void highCpsFlagsAutoClicker() {
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 17; i++) {
            results = check.onCombat(combat("a-cps", "t", 1.0, 2000 + i * 10L, 0));
        }
        assertTrue(hasCheat(results, "AUTO_CLICKER"));
    }

    @Test
    void hitOutsideViewFrustumFlagsKillAura() {
        List<DetectionResult> results = check.onCombat(combat("a-angle", "t", 1.0, 3000, 120));
        assertTrue(hasCheat(results, "KILL_AURA"));
    }

    @Test
    void rapidTargetSwitchingFlagsKillAura() {
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 6; i++) {
            results = check.onCombat(combat("a-switch", "t" + i, 1.0, 4000 + i * 10L, 0));
        }
        assertTrue(hasCheat(results, "KILL_AURA"));
    }

    private static CombatEvent combat(String attacker, String target, double reach, long ts, double angle) {
        return new CombatEvent(attacker, target, new Position(0, 64, 0), new Position(reach, 64, 0), ts, false, angle);
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(r -> r.checkType() == CheckType.COMBAT && cheatType.equals(r.cheatType()));
    }
}
