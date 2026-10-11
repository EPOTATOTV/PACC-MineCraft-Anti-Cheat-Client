package com.potatotv.pacc.addon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonScannerTest {

    @TempDir
    Path root;

    private final AddonScanner scanner = new AddonScanner(AddonSignatureStore.builder()
            .addUuid("uuid-evil")
            .addKeyword("cheatpack")
            .build());

    // ---------- 行为包 ----------

    @Test
    void cheatUuidIsFlagged() throws IOException {
        Path pack = root.resolve("behavior_packs").resolve("evil");
        write(pack.resolve("manifest.json"), manifest("uuid-evil", "Evil Pack", "[1,0,0]", "[{\"type\":\"data\"}]"));

        ScanReport report = scanner.scan(root);

        ScannedAddon addon = only(report.addons());
        assertEquals("uuid-evil", addon.uuid());
        assertEquals("Evil Pack", addon.name());
        assertEquals("1.0.0", addon.version());
        assertEquals(PackKind.BEHAVIOR, addon.packKind());
        assertTrue(addon.knownCheat());
        assertTrue(addon.suspicious());
        assertTrue(addon.moduleTypes().contains("data"));
        assertNotNull(addon.sha256());
    }

    @Test
    void suspiciousComponentsAreDetected() throws IOException {
        Path pack = root.resolve("behavior_packs").resolve("hacked");
        write(pack.resolve("manifest.json"), manifest("uuid-1", "Speed Pack", "[2,0,0]", "[{\"type\":\"data\"}]"));
        write(pack.resolve("entities").resolve("player.json"), """
                {"format_version":"1.16.0","minecraft:entity":{"components":{
                  "minecraft:physics":{"has_gravity":false},
                  "minecraft:damage_sensor":{"triggers":[]},
                  "minecraft:attack":{"damage":100},
                  "minecraft:movement":{"value":0.5}}}}""");

        ScannedAddon addon = only(scanner.scan(root).addons());

        assertTrue(addon.components().contains("minecraft:physics"));
        assertTrue(addon.components().contains("minecraft:damage_sensor"));
        assertTrue(addon.components().contains("minecraft:attack"));
        assertTrue(addon.components().contains("minecraft:movement"));
        assertTrue(addon.suspicious());
        assertFalse(addon.knownCheat());
    }

    @Test
    void scriptKeywordsAreDetected() throws IOException {
        Path pack = root.resolve("behavior_packs").resolve("scriptpack");
        write(pack.resolve("manifest.json"), manifest("uuid-2", "Script Pack", "[1,0,0]", "[{\"type\":\"script\"}]"));
        write(pack.resolve("scripts").resolve("main.js"),
                "function tick(){ if (killaura) fly(); setGamemode('creative'); }");

        ScannedAddon addon = only(scanner.scan(root).addons());

        assertTrue(addon.scriptKeywords().contains("killaura"));
        assertTrue(addon.scriptKeywords().contains("fly"));
        assertTrue(addon.scriptKeywords().contains("gamemode"));
        assertTrue(addon.moduleTypes().contains("script"));
        assertTrue(addon.suspicious());
    }

    // ---------- 资源包 ----------

    @Test
    void xrayResourcePackIsFlagged() throws IOException {
        Path pack = root.resolve("resource_packs").resolve("better_ores");
        write(pack.resolve("manifest.json"), manifest("uuid-3", "Better Ores", "[1,0,0]", "[{\"type\":\"resources\"}]"));
        write(pack.resolve("textures").resolve("xray_ore.png"), "binary");

        ScannedAddon addon = only(scanner.scan(root).addons());

        assertTrue(addon.components().contains("xray"));
        assertTrue(addon.suspicious());
        assertEquals(PackKind.RESOURCE, addon.packKind());
    }

    @Test
    void fullbrightResourcePackIsFlagged() throws IOException {
        Path pack = root.resolve("resource_packs").resolve("night");
        write(pack.resolve("manifest.json"), manifest("uuid-4", "Night Vision", "[1,0,0]", "[]"));
        write(pack.resolve("fullbright.json"), "{}");

        ScannedAddon addon = only(scanner.scan(root).addons());

        assertTrue(addon.components().contains("fullbright"));
        assertTrue(addon.suspicious());
    }

    @Test
    void nameKeywordIsFlagged() throws IOException {
        Path pack = root.resolve("behavior_packs").resolve("cp");
        write(pack.resolve("manifest.json"), manifest("uuid-5", "CheatPack Deluxe", "[1,0,0]", "[]"));

        ScannedAddon addon = only(scanner.scan(root).addons());

        assertTrue(addon.components().contains("keyword:cheatpack"));
        assertTrue(addon.suspicious());
    }

    @Test
    void sha256SignatureIsFlagged() throws IOException {
        Path pack = root.resolve("behavior_packs").resolve("known");
        String content = manifest("uuid-6", "Known Pack", "[1,0,0]", "[]");
        write(pack.resolve("manifest.json"), content);
        String sha = sha256(content);

        AddonScanner shaScanner = new AddonScanner(AddonSignatureStore.builder()
                .addSha256(sha).build());
        ScannedAddon addon = only(shaScanner.scan(root).addons());

        assertTrue(addon.knownCheat());
        assertTrue(addon.suspicious());
        assertEquals(sha, addon.sha256());
    }

    // ---------- 不误报 ----------

    @Test
    void benignPacksAreNotFlagged() throws IOException {
        Path behavior = root.resolve("behavior_packs").resolve("nice");
        write(behavior.resolve("manifest.json"), manifest("uuid-ok-b", "Nice Addon", "[1,0,0]", "[{\"type\":\"data\"}]"));
        write(behavior.resolve("entities").resolve("mob.json"), """
                {"format_version":"1.16.0","minecraft:entity":{"components":{
                  "minecraft:health":{"value":20},
                  "minecraft:collision_box":{"width":0.6,"height":1.8}}}}""");

        Path resource = root.resolve("resource_packs").resolve("vanilla");
        write(resource.resolve("manifest.json"), manifest("uuid-ok-r", "Vanilla Tweaks", "[1,0,0]", "[{\"type\":\"resources\"}]"));
        write(resource.resolve("textures").resolve("blocks").resolve("stone.png"), "binary");

        ScanReport report = scanner.scan(root);

        assertEquals(2, report.addons().size());
        assertTrue(report.suspiciousAddons().isEmpty());
        for (ScannedAddon addon : report.addons()) {
            assertFalse(addon.suspicious());
            assertFalse(addon.knownCheat());
        }
    }

    // ---------- 健壮性 ----------

    @Test
    void malformedManifestIsSkipped() throws IOException {
        Path broken = root.resolve("behavior_packs").resolve("broken");
        write(broken.resolve("manifest.json"), "{ this is not json ");
        Path good = root.resolve("behavior_packs").resolve("good");
        write(good.resolve("manifest.json"), manifest("uuid-good", "Good", "[1,0,0]", "[]"));

        ScanReport report = scanner.scan(root);

        assertEquals(1, report.addons().size());
        assertEquals("uuid-good", report.addons().get(0).uuid());
    }

    @Test
    void oversizedManifestIsSkipped() throws IOException {
        Path pack = root.resolve("behavior_packs").resolve("huge");
        Path manifest = pack.resolve("manifest.json");
        Files.createDirectories(pack);
        byte[] big = new byte[9 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(manifest, big);

        ScanReport report = scanner.scan(root);

        assertTrue(report.addons().isEmpty());
    }

    @Test
    void missingRootReturnsEmptyReport() {
        ScanReport report = scanner.scan(root.resolve("does-not-exist"));
        assertTrue(report.addons().isEmpty());
        assertTrue(report.worlds().isEmpty());
    }

    // ---------- 世界绑定 ----------

    @Test
    void worldBindingsAreDetected() throws IOException {
        Path world = root.resolve("minecraftWorlds").resolve("MyWorld");
        write(world.resolve("world_behavior_packs.json"), "[{\"pack_id\":\"uuid-evil\",\"version\":[1,0,0]}]");
        write(world.resolve("world_resource_packs.json"),
                "{\"packs\":[{\"pack_id\":\"uuid-rp\",\"version\":[1,0,0]}]}");
        // 非世界目录应被忽略
        write(root.resolve("minecraftWorlds").resolve("NotAWorld").resolve("level.dat"), "x");

        ScanReport report = scanner.scan(root);

        assertEquals(1, report.worlds().size());
        WorldBinding binding = report.worlds().get(0);
        assertEquals("MyWorld", binding.worldName());
        assertTrue(binding.behaviorPackUuids().contains("uuid-evil"));
        assertTrue(binding.resourcePackUuids().contains("uuid-rp"));
    }

    // ---------- 特征库加载 ----------

    @Test
    void signatureStoreLoadsFromJson() {
        AddonSignatureStore store = AddonSignatureStore.loadFromJson(
                "{\"uuids\":[\"UUID-A\"],\"sha256\":[\"ABC\"],\"keywords\":[\"FlyHack\"]}");

        assertTrue(store.isCheatUuid("uuid-a"));
        assertTrue(store.isCheatSha256("abc"));
        assertTrue(store.containsNameKeyword("My Flyhack Pack"));
        assertTrue(store.nameKeywords("Flyhack").contains("flyhack"));
    }

    @Test
    void brokenSignatureJsonYieldsEmptyStore() {
        AddonSignatureStore store = AddonSignatureStore.loadFromJson("{ not json");
        assertTrue(store.cheatUuids().isEmpty());
        assertTrue(store.cheatSha256().isEmpty());
        assertTrue(store.cheatKeywords().isEmpty());
    }

    // ---------- JSON 解析器 ----------

    @Test
    void jsonParsesNestedStructures() {
        Object parsed = Json.parse("{\"a\":{\"b\":[1,2,{\"c\":true}]},\"d\":null}");
        assertTrue(parsed instanceof java.util.Map<?, ?>);
    }

    @Test
    void jsonRejectsExcessiveDepth() {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep, 10));
    }

    // ---------- 根目录解析 ----------

    @Test
    void addonRootResolvesByPlatform() {
        Path win = AddonRoot.resolve(Platform.WINDOWS, "C:\\Users\\me\\AppData\\Local");
        assertTrue(win.toString().replace('\\', '/')
                .endsWith("Microsoft.MinecraftUWP_8wekyb3d8bbwe/LocalState/games/com.mojang"));

        Path android = AddonRoot.resolve(Platform.ANDROID, null);
        assertEquals("/storage/emulated/0/games/com.mojang", android.toString().replace('\\', '/'));

        assertEquals(null, AddonRoot.resolve(Platform.OTHER, null));
    }

    // ---------- 辅助 ----------

    private static String manifest(String uuid, String name, String version, String modules) {
        return "{\"format_version\":2,\"header\":{\"name\":\"" + name + "\",\"uuid\":\"" + uuid
                + "\",\"version\":" + version + "},\"modules\":" + modules + "}";
    }

    private static ScannedAddon only(List<ScannedAddon> addons) {
        assertEquals(1, addons.size());
        return addons.get(0);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String sha256(String content) throws IOException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
