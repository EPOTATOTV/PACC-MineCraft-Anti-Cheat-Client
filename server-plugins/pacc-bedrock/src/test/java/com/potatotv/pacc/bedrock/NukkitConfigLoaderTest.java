package com.potatotv.pacc.bedrock;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;

import cn.nukkit.utils.Config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NukkitConfigLoaderTest {

    private static NukkitConfigLoader load(String yaml) throws Exception {
        Path file = Files.createTempFile("pacc-nukkit-config", ".yml");
        file.toFile().deleteOnExit();
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        Config cfg = new Config(file.toFile(), Config.YAML);
        return new NukkitConfigLoader(cfg, ServerType.NUKKIT, "6.0.0", "1.0.14", "TestServer", 20, 19132);
    }

    @Test
    void emptyConfigUsesDefaults() throws Exception {
        ServerPluginConfig config = load("").serverPluginConfig();
        assertEquals(0, config.punishmentMode());
        assertEquals(PunishmentType.ALERT, config.maxPunishment());
        assertEquals(60, config.confidenceThreshold());
        assertEquals(3.1, config.maxReachDistance(), 1e-9);
        assertEquals(16, config.maxCps());
    }

    @Test
    void enabledChecksAreParsedCaseInsensitively() throws Exception {
        ServerPluginConfig config = load("enabled-checks:\n  - MOVE\n  - combat\n").serverPluginConfig();
        assertTrue(config.isEnabled(CheckType.MOVE));
        assertTrue(config.isEnabled(CheckType.COMBAT));
        assertFalse(config.isEnabled(CheckType.PACKET));
    }

    @Test
    void missingEnabledChecksKeepsAllEnabled() throws Exception {
        ServerPluginConfig config = load("punishment-mode: 0\n").serverPluginConfig();
        assertTrue(config.isEnabled(CheckType.ADDON));
        assertTrue(config.isEnabled(CheckType.DATA_CONSISTENCY));
    }

    @Test
    void punishmentModeMapsToMaxPunishment() throws Exception {
        assertEquals(PunishmentType.KICK, load("punishment-mode: 1\n").serverPluginConfig().maxPunishment());
        assertEquals(PunishmentType.BAN, load("punishment-mode: 2\n").serverPluginConfig().maxPunishment());
    }

    @Test
    void serverIdIsDerivedFromNameAndPortWhenBlank() throws Exception {
        assertEquals("testserver:19132", load("server-id: \"\"\n").serverInfo().serverId());
        assertEquals("my-id", load("server-id: my-id\n").serverInfo().serverId());
    }

    @Test
    void ptvFieldsAreParsedAndServerIdFallsBack() throws Exception {
        NukkitConfigLoader loader = load("ptv:\n  url: \"https://ptv.example\"\n  secret: \"s3cr3t\"\n  server-id: \"\"\n");
        assertEquals("https://ptv.example", loader.ptvUrl());
        assertEquals("s3cr3t", loader.ptvSecret());
        assertEquals("testserver:19132", loader.ptvServerId());
    }

    @Test
    void cheatModHashesAreNormalizedAndInvalidEntriesDropped() throws Exception {
        String upper = "A".repeat(64);
        ServerPluginConfig config = load(
                "cheat-mod-hashes:\n  - \"" + upper + "\"\n  - \"not-a-hash\"\n").serverPluginConfig();
        assertEquals(java.util.Set.of("a".repeat(64)), config.cheatModHashes());
    }
}
