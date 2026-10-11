package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.AddonCheck;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonCheckTest {

    private final ServerPluginConfig config = ServerPluginConfig.builder()
            .cheatAddonUuids(Set.of("uuid-evil"))
            .cheatAddonKeywords(Set.of("autoclicker"))
            .build();
    private final AddonCheck check = new AddonCheck(config);

    @Test
    void cheatUuidFlagsCheatAddon() {
        List<DetectionResult> results = check.onAddons("p", List.of(
                addon("uuid-evil", "Evil Pack", Set.of(), Set.of())));
        assertTrue(hasCheat(results, "CHEAT_ADDON"));
    }

    @Test
    void cheatScriptKeywordFlagsCheatAddonScript() {
        List<DetectionResult> results = check.onAddons("p", List.of(
                addon("uuid-x", "Some Pack", Set.of("AutoClicker"), Set.of())));
        assertTrue(hasCheat(results, "CHEAT_ADDON_SCRIPT"));
    }

    @Test
    void xrayResourcePackIsFlagged() {
        List<DetectionResult> results = check.onAddons("p", List.of(
                addon("uuid-xray", "XRay Pack", Set.of(), Set.of())));
        assertTrue(hasCheat(results, "CHEAT_ADDON_XRAY"));
    }

    @Test
    void benignAddonIsClean() {
        List<DetectionResult> results = check.onAddons("p", List.of(
                addon("uuid-ok", "Nice Addon", Set.of(), Set.of())));
        assertTrue(results.isEmpty());
    }

    private static AddonInfo addon(String uuid, String name, Set<String> keywords, Set<String> components) {
        return new AddonInfo(uuid, name, "1.0.0", "sha", keywords, components);
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(r -> r.checkType() == CheckType.ADDON && cheatType.equals(r.cheatType()));
    }
}
