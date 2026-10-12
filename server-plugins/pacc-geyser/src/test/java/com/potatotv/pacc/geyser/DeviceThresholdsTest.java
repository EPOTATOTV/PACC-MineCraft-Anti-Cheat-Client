package com.potatotv.pacc.geyser;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceThresholdsTest {

    @Test
    void classifiesByPlatformName() {
        assertEquals(DeviceThresholds.DeviceClass.MOBILE, DeviceThresholds.classify("ANDROID", null));
        assertEquals(DeviceThresholds.DeviceClass.DESKTOP, DeviceThresholds.classify("WIN32", null));
        assertEquals(DeviceThresholds.DeviceClass.CONSOLE, DeviceThresholds.classify("XBOX", null));
        assertEquals(DeviceThresholds.DeviceClass.VR, DeviceThresholds.classify("GEARVR", null));
        assertEquals(DeviceThresholds.DeviceClass.MOBILE, DeviceThresholds.classify("GOOGLE", null));
        assertEquals(DeviceThresholds.DeviceClass.UNKNOWN, DeviceThresholds.classify(null, null));
    }

    @Test
    void classifiesByDeviceOsText() {
        assertEquals(DeviceThresholds.DeviceClass.MOBILE, DeviceThresholds.classify(null, "Android 13"));
        assertEquals(DeviceThresholds.DeviceClass.CONSOLE, DeviceThresholds.classify(null, "Nintendo Switch"));
    }

    @Test
    void mobileProfileIsMoreLenientThanDesktop() {
        DeviceThresholds.Profile mobile = DeviceThresholds.profileFor(DeviceThresholds.DeviceClass.MOBILE);
        DeviceThresholds.Profile desktop = DeviceThresholds.profileFor(DeviceThresholds.DeviceClass.DESKTOP);
        assertTrue(mobile.speedFactor() > desktop.speedFactor());
        assertTrue(mobile.reachDelta() > desktop.reachDelta());
        assertTrue(mobile.cpsDelta() > desktop.cpsDelta());
    }

    @Test
    void adjustScalesThresholdsAndKeepsOtherFields() {
        ServerPluginConfig base = ServerPluginConfig.builder()
                .punishmentMode(1)
                .enabledChecks(java.util.EnumSet.of(CheckType.MOVE))
                .build();
        ServerPluginConfig adjusted = DeviceThresholds.adjust(base, DeviceThresholds.DeviceClass.MOBILE);

        assertEquals(base.maxHorizontalSpeedPerTick() * 1.25, adjusted.maxHorizontalSpeedPerTick(), 1e-9);
        assertEquals(base.maxReachDistance() + 0.2, adjusted.maxReachDistance(), 1e-9);
        assertEquals(base.maxCps() + 2, adjusted.maxCps());
        assertEquals(Math.round(base.flyAirborneTicksTrigger() * 1.15), adjusted.flyAirborneTicksTrigger());
        // 处置级别与启用项必须原样保留。
        assertEquals(1, adjusted.punishmentMode());
        assertTrue(adjusted.isEnabled(CheckType.MOVE));
        assertEquals(false, adjusted.isEnabled(CheckType.COMBAT));
    }

    @Test
    void adjustKeepsCheatModHashes() {
        Set<String> hashes = Set.of("a".repeat(64));
        ServerPluginConfig base = ServerPluginConfig.builder().cheatModHashes(hashes).build();
        ServerPluginConfig adjusted = DeviceThresholds.adjust(base, DeviceThresholds.DeviceClass.MOBILE);
        assertEquals(hashes, adjusted.cheatModHashes());
    }

    @Test
    void unknownDeviceLeavesThresholdsUnchanged() {
        ServerPluginConfig base = ServerPluginConfig.defaults();
        ServerPluginConfig adjusted = DeviceThresholds.adjust(base, DeviceThresholds.DeviceClass.UNKNOWN);
        assertEquals(base.maxHorizontalSpeedPerTick(), adjusted.maxHorizontalSpeedPerTick(), 1e-9);
        assertEquals(base.maxCps(), adjusted.maxCps());
        assertEquals(base.maxReachDistance(), adjusted.maxReachDistance(), 1e-9);
    }

    @Test
    void mostLenientPicksWidestProfile() {
        assertEquals(DeviceThresholds.DeviceClass.MOBILE,
                DeviceThresholds.mostLenient(List.of(DeviceThresholds.DeviceClass.DESKTOP,
                        DeviceThresholds.DeviceClass.MOBILE)));
        assertEquals(DeviceThresholds.DeviceClass.UNKNOWN, DeviceThresholds.mostLenient(List.of()));
        assertEquals(DeviceThresholds.DeviceClass.UNKNOWN, DeviceThresholds.mostLenient(null));
    }
}
