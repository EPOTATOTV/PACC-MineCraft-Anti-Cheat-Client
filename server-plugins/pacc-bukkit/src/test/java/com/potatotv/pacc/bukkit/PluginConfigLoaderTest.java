package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigLoaderTest {

    private static PluginConfigLoader load(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return new PluginConfigLoader(cfg, ServerType.PAPER, "6.0.0", "1.21.1", "TestServer", 20, 25565);
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
        assertEquals("testserver:25565", load("server-id: \"\"\n").serverInfo().serverId());
        assertEquals("my-id", load("server-id: my-id\n").serverInfo().serverId());
    }

    @Test
    void ptvFieldsAreParsedAndServerIdFallsBack() throws Exception {
        PluginConfigLoader loader = load("ptv:\n  url: \"https://ptv.example\"\n  secret: \"s3cr3t\"\n  server-id: \"\"\n");
        assertEquals("https://ptv.example", loader.ptvUrl());
        assertEquals("s3cr3t", loader.ptvSecret());
        assertEquals("testserver:25565", loader.ptvServerId());
    }

    @Test
    void cheatModHashesAreNormalizedAndInvalidEntriesDropped() throws Exception {
        String upper = "A".repeat(64);
        ServerPluginConfig config = load(
                "cheat-mod-hashes:\n  - \"" + upper + "\"\n  - \"not-a-hash\"\n").serverPluginConfig();
        assertEquals(Set.of("a".repeat(64)), config.cheatModHashes());
    }

    @Test
    void missingCheatModHashesKeepsEmpty() throws Exception {
        assertTrue(load("punishment-mode: 0\n").serverPluginConfig().cheatModHashes().isEmpty());
    }
}
