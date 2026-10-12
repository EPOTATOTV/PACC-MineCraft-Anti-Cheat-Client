package com.potatotv.pacc.server.core;

import com.potatotv.pacc.addon.AddonSignatureStore;
import com.potatotv.pacc.addon.PackKind;
import com.potatotv.pacc.addon.ScannedAddon;
import com.potatotv.pacc.server.core.check.AddonCheck;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void emptyAddonListIsClean() {
        assertTrue(check.onAddons("p", List.of()).isEmpty());
        assertTrue(check.onAddons("p", null).isEmpty());
    }

    @Test
    void cheatSha256FlagsHighConfidenceAboveUuidMatch() {
        AddonSignatureStore signatures = AddonSignatureStore.builder()
                .addSha256("deadbeef")
                .build();
        AddonCheck hashed = new AddonCheck(config, signatures);
        // 改名换 uuid 都一样能命中：哈希来自 manifest 内容。
        List<DetectionResult> results = hashed.onAddons("p", List.of(
                addonWithSha("uuid-renamed", "Totally Innocent", "DEADBEEF", Set.of(), Set.of())));
        assertEquals(95, confidenceOf(results, "CHEAT_ADDON"));
        assertTrue(confidenceOf(results, "CHEAT_ADDON") > 90);
    }

    @Test
    void newSuspiciousComponentsFlagCheatAddon() {
        List<DetectionResult> movement = check.onAddons("p", List.of(
                addon("uuid-move", "Move Pack", Set.of(), Set.of("minecraft:movement"))));
        assertTrue(hasCheat(movement, "CHEAT_ADDON"));

        List<DetectionResult> attack = check.onAddons("p", List.of(
                addon("uuid-attack", "Attack Pack", Set.of(), Set.of("minecraft:attack"))));
        assertTrue(hasCheat(attack, "CHEAT_ADDON"));
    }

    @Test
    void fullbrightMarkerFlagsLowConfidence() {
        List<DetectionResult> results = check.onAddons("p", List.of(
                addon("uuid-fb", "Bright Pack", Set.of(), Set.of("fullbright"))));
        assertTrue(hasCheat(results, "CHEAT_ADDON_FULLBRIGHT"));
        assertEquals(50, confidenceOf(results, "CHEAT_ADDON_FULLBRIGHT"));
    }

    @Test
    void addonInfoFromScannedAddonMapsFields() {
        ScannedAddon scanned = new ScannedAddon("uuid-1", "Pack", "1.2.3", PackKind.BEHAVIOR,
                Path.of("pack"), "hash", Set.of("script"), Set.of("fly"),
                Set.of("minecraft:movement"), true, true);
        AddonInfo info = AddonInfo.from(scanned);
        assertEquals("uuid-1", info.uuid());
        assertEquals("Pack", info.name());
        assertEquals("1.2.3", info.version());
        assertEquals("hash", info.sha256());
        assertEquals(Set.of("fly"), info.scriptKeywords());
        assertEquals(Set.of("minecraft:movement"), info.components());
    }

    @Test
    void addonInfoFromScannedAddonWithNullSha256IsSafe() {
        ScannedAddon scanned = new ScannedAddon("uuid-2", "Res", null, PackKind.RESOURCE,
                Path.of("pack"), null, Set.of(), Set.of(), Set.of(), false, false);
        AddonInfo info = AddonInfo.from(scanned);
        assertNull(info.sha256());
        assertTrue(info.components().isEmpty());
    }

    private static AddonInfo addon(String uuid, String name, Set<String> keywords, Set<String> components) {
        return new AddonInfo(uuid, name, "1.0.0", "sha", keywords, components);
    }

    private static AddonInfo addonWithSha(
            String uuid, String name, String sha256, Set<String> keywords, Set<String> components) {
        return new AddonInfo(uuid, name, "1.0.0", sha256, keywords, components);
    }

    private static boolean hasCheat(List<DetectionResult> results, String cheatType) {
        return results.stream().anyMatch(r -> r.checkType() == CheckType.ADDON && cheatType.equals(r.cheatType()));
    }

    private static int confidenceOf(List<DetectionResult> results, String cheatType) {
        return results.stream()
                .filter(r -> r.checkType() == CheckType.ADDON && cheatType.equals(r.cheatType()))
                .mapToInt(DetectionResult::confidence)
                .findFirst()
                .orElse(-1);
    }
}
