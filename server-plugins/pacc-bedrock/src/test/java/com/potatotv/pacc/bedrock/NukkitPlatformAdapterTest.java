package com.potatotv.pacc.bedrock;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 处置策略的纯逻辑单测：封禁时长换算。真正的 kick / ban 需要活的 Nukkit 运行时，不在这里测。
 */
class NukkitPlatformAdapterTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void positiveDurationProducesTimedExpiry() {
        Date expires = NukkitPlatformAdapter.banExpiry(3_600_000L, NOW);
        assertEquals(NOW + 3_600_000L, expires.getTime());
    }

    @Test
    void zeroDurationIsPermanent() {
        assertNull(NukkitPlatformAdapter.banExpiry(0L, NOW));
    }

    @Test
    void negativeDurationIsPermanent() {
        assertNull(NukkitPlatformAdapter.banExpiry(-1L, NOW));
    }
}
