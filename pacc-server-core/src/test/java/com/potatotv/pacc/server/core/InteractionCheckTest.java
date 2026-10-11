package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.InteractionCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionCheckTest {

    private final ServerPluginConfig config = ServerPluginConfig.defaults();
    private final InteractionCheck check = new InteractionCheck(config);

    @Test
    void twoPlacesInSameTickIsFlagged() {
        // 同一 50ms 刻内放置两次；pos 传 null 以隔离 FAST_PLACE，不受堆叠判定干扰
        assertTrue(check.onInteract("p1", null, "PLACE", 1000L).isEmpty());
        assertTrue(hasCheat(check.onInteract("p1", null, "PLACE", 1010L), "FAST_PLACE"));
    }

    @Test
    void singlePlaceIsClean() {
        assertTrue(check.onInteract("p2", null, "PLACE", 1000L).isEmpty());
    }

    @Test
    void consecutiveDownwardStackIsFlaggedAsScaffold() {
        // 每步间隔 60ms（<100ms 窗口），y 逐格下降，第 3 次触发
        assertTrue(check.onInteract("p3", new Position(0, 65, 0), "PLACE", 1000L).isEmpty());
        assertTrue(check.onInteract("p3", new Position(0, 64, 0), "PLACE", 1060L).isEmpty());
        assertTrue(hasCheat(check.onInteract("p3", new Position(0, 63, 0), "PLACE", 1120L), "SCAFFOLD"));
    }

    @Test
    void nonPlaceActionAndNullPlayerAreClean() {
        assertTrue(check.onInteract("p4", new Position(0, 64, 0), "BREAK", 1000L).isEmpty());
        assertTrue(check.onInteract(null, new Position(0, 64, 0), "PLACE", 1000L).isEmpty());
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(
                r -> r.checkType() == CheckType.INTERACTION && cheatType.equals(r.cheatType()));
    }
}
