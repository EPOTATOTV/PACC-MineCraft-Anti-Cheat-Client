package com.potatotv.pacc.detection.server.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-01 / R-08 版本矩阵门禁：CI 用 {@code -Dpacc.mc.version=26.1|26.2|26.3|26.4}
 * 跑这个类，保证版本解析、世界高度与硫磺/Vulkan 能力声明跟得上编排矩阵。
 */
class McVersionMatrixTest {

    @Test
    void requestedVersionParsesToItself() {
        String requested = System.getProperty("pacc.mc.version", MinecraftVersion.latest().displayName());
        MinecraftVersion version = MinecraftVersion.parse(requested);
        assertEquals(requested, version.displayName(), "版本号解析必须与 CI 矩阵一致");
    }

    @Test
    void modernWorldHeightIsUniformAcross26x() {
        assertEquals(-64, MinecraftVersion.MIN_Y);
        assertEquals(319, MinecraftVersion.MAX_Y);
        assertTrue(WorldReplica.inRange(MinecraftVersion.MIN_Y));
        assertTrue(WorldReplica.inRange(MinecraftVersion.MAX_Y));
        assertTrue(!WorldReplica.inRange(MinecraftVersion.MIN_Y - 1));
        assertTrue(!WorldReplica.inRange(MinecraftVersion.MAX_Y + 1));
    }

    @Test
    void sulfurBlocksBelongTo26_2() {
        assertTrue(MinecraftVersion.V26_2.hasSulfurBlocks());
        assertTrue(!MinecraftVersion.V26_1.hasSulfurBlocks());
        assertEquals(BlockPhysics.SOLID, BlockPhysics.forMaterial("SULFUR_BLOCK"));
        assertEquals(BlockPhysics.SOLID, BlockPhysics.forMaterial("SULFUR_SPIKE"));
    }
}
