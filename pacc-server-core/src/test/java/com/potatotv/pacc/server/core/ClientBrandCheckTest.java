package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.ClientBrandCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBrandCheckTest {

    private final ServerPluginConfig config = ServerPluginConfig.builder()
            .cheatClientBrands(Set.of("wurst"))
            .cheatModNames(Set.of("impact", "baritone"))
            .build();
    private final ClientBrandCheck check = new ClientBrandCheck(config);

    @Test
    void cheatBrandIsCaseInsensitiveHit() {
        List<DetectionResult> results = check.onClientBrand("p1", "WURST");
        assertEquals(1, results.size());
        assertTrue(hasCheat(results, "CHEAT_CLIENT"));
    }

    @Test
    void vanillaBrandIsClean() {
        assertTrue(check.onClientBrand("p1", "vanilla").isEmpty());
    }

    @Test
    void nullBrandIsClean() {
        assertTrue(check.onClientBrand("p1", null).isEmpty());
    }

    @Test
    void listedModIsFlagged() {
        List<DetectionResult> results = check.onMods("p1", List.of("fabric-api", "Baritone"));
        assertEquals(1, results.size());
        assertTrue(hasCheat(results, "CHEAT_MOD"));
    }

    @Test
    void unlistedModsAreClean() {
        assertTrue(check.onMods("p1", List.of("sodium", "lithium")).isEmpty());
        assertTrue(check.onMods("p1", List.of()).isEmpty());
        assertTrue(check.onMods("p1", null).isEmpty());
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(
                r -> r.checkType() == CheckType.CLIENT_BRAND && cheatType.equals(r.cheatType()));
    }
}
