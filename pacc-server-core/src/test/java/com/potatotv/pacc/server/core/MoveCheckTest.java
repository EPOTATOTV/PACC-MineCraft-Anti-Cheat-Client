package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.MoveCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveCheckTest {

    private final ServerPluginConfig config = ServerPluginConfig.defaults();
    private final MoveCheck check = new MoveCheck(config);

    @Test
    void horizontalSpeedOverLimitFlagsSpeed() {
        List<DetectionResult> results = check.onMove(
                pos("p-speed", 0, 64, 0, 1000, true, GameMode.SURVIVAL),
                pos("p-speed", 1.0, 64, 0, 1050, true, GameMode.SURVIVAL));
        assertTrue(hasCheat(results, "SPEED"));
    }

    @Test
    void normalHorizontalStepIsClean() {
        List<DetectionResult> results = check.onMove(
                pos("p-normal", 0, 64, 0, 1000, true, GameMode.SURVIVAL),
                pos("p-normal", 0.3, 64, 0, 1050, true, GameMode.SURVIVAL));
        assertTrue(results.isEmpty());
    }

    @Test
    void creativeModeIsSkipped() {
        List<DetectionResult> results = check.onMove(
                pos("p-creative", 0, 64, 0, 1000, true, GameMode.CREATIVE),
                pos("p-creative", 5.0, 70, 0, 1050, false, GameMode.CREATIVE));
        assertFalse(hasCheat(results, "SPEED"));
        assertFalse(hasCheat(results, "FLY"));
    }

    @Test
    void airborneClimbFlagsFly() {
        List<DetectionResult> results = check.onMove(
                pos("p-fly", 0, 64, 0, 1000, false, GameMode.SURVIVAL),
                pos("p-fly", 0, 65, 0, 1050, false, GameMode.SURVIVAL));
        assertTrue(hasCheat(results, "FLY"));
    }

    private static PlayerPosition pos(
            String id, double x, double y, double z, long ts, boolean onGround, GameMode mode) {
        return new PlayerPosition(id, new Position(x, y, z), 0f, 0f, ts, onGround, mode);
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(r -> r.checkType() == CheckType.MOVE && cheatType.equals(r.cheatType()));
    }
}
