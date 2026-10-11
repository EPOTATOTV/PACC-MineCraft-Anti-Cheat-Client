package com.potatotv.pacc.bukkit;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 处置策略的纯逻辑单测：封禁时长换算。真正的 kick / ban 需要活的 Bukkit 运行时，不在这里测。
 */
class BukkitPlatformAdapterTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void positiveDurationProducesTimedExpiry() {
        Date expires = BukkitPlatformAdapter.banExpiry(3_600_000L, NOW);
        assertEquals(NOW + 3_600_000L, expires.getTime());
    }

    @Test
    void zeroDurationIsPermanent() {
        assertNull(BukkitPlatformAdapter.banExpiry(0L, NOW));
    }

    @Test
    void negativeDurationIsPermanent() {
        assertNull(BukkitPlatformAdapter.banExpiry(-1L, NOW));
    }
}
