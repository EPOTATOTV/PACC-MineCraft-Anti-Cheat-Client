package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.server.prediction.WorldReplica;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P-01 世界副本喂入：方块变更按世界名共享，玩家通过移动事件绑定世界。
 */
class WorldReplicaFeedTest {

    @Test
    void blockChangeIsVisibleToPlayersBoundToSameWorld() {
        IntelRuntime runtime = new IntelRuntime();
        runtime.bindPlayerWorld("p1", "world");
        runtime.bindPlayerWorld("p2", "world");

        runtime.applyBlockChange("world", 10, 64, 10, "SULFUR_BLOCK");

        WorldReplica p1 = runtime.existingWorld("p1");
        WorldReplica p2 = runtime.existingWorld("p2");
        assertNotNull(p1);
        assertNotNull(p2);
        assertEquals("SULFUR_BLOCK", p1.materialAt(10, 64, 10));
        assertEquals("SULFUR_BLOCK", p2.materialAt(10, 64, 10));
        assertEquals(0.6, p1.physicsAt(10, 64, 10).slipperiness(), 1e-9);
    }

    @Test
    void worldsAreIsolatedByName() {
        IntelRuntime runtime = new IntelRuntime();
        runtime.bindPlayerWorld("overworld", "world");
        runtime.bindPlayerWorld("nether", "world_nether");

        runtime.applyBlockChange("world", 0, 64, 0, "STONE");
        runtime.worldByName("world_nether"); // 下界世界已创建但还没有方块变更

        assertNotNull(runtime.existingWorld("overworld"));
        assertNotNull(runtime.existingWorld("nether"));
        assertEquals("STONE", runtime.existingWorld("overworld").materialAt(0, 64, 0));
        assertNull(runtime.existingWorld("nether").materialAt(0, 64, 0));
    }

    @Test
    void unboundPlayerHasNoWorld() {
        IntelRuntime runtime = new IntelRuntime();
        assertNull(runtime.existingWorld("unknown"));
        runtime.bindPlayerWorld("p", "world");
        runtime.clear("p");
        assertNull(runtime.existingWorld("p"));
    }
}
