package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPluginConfigTest {

    @Test
    void defaultsAreAlertOnlyWithAllChecksEnabled() {
        ServerPluginConfig config = ServerPluginConfig.defaults();
        assertEquals(PunishmentType.ALERT, config.maxPunishment());
        assertEquals(0, config.punishmentMode());
        for (CheckType type : CheckType.values()) {
            assertTrue(config.isEnabled(type), "默认应开启检查项 " + type);
        }
    }

    @Test
    void punishmentModeMapsToKickAndBan() {
        assertEquals(PunishmentType.KICK,
                ServerPluginConfig.builder().punishmentMode(1).build().maxPunishment());
        assertEquals(PunishmentType.BAN,
                ServerPluginConfig.builder().punishmentMode(2).build().maxPunishment());
    }

    @Test
    void enabledChecksCanBeRestricted() {
        ServerPluginConfig config = ServerPluginConfig.builder()
                .enabledChecks(Set.of(CheckType.MOVE))
                .build();
        assertTrue(config.isEnabled(CheckType.MOVE));
        assertFalse(config.isEnabled(CheckType.COMBAT));
    }

    @Test
    void defaultThresholdsMatchSpec() {
        ServerPluginConfig config = ServerPluginConfig.defaults();
        assertEquals(0.42, config.maxHorizontalSpeedPerTick());
        assertEquals(0.42, config.maxVerticalClimbPerTick());
        assertEquals(3.1, config.maxReachDistance());
        assertEquals(16, config.maxCps());
        assertEquals(1, config.maxPlacePerTick());
        assertEquals(3.0, config.maxFallDistanceNoDamage());
        assertEquals(20, config.flyAirborneTicksTrigger());
        assertEquals(60, config.confidenceThreshold());
    }

    @Test
    void cheatModHashesDefaultEmptyAndSettable() {
        assertTrue(ServerPluginConfig.defaults().cheatModHashes().isEmpty());
        Set<String> hashes = Set.of("a".repeat(64));
        assertEquals(hashes, ServerPluginConfig.builder().cheatModHashes(hashes).build().cheatModHashes());
    }
}
