package com.potatotv.pacc.detection.server.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldReplicaTest {

    @Test
    void blockBoxContainsAndExpand() {
        BlockBox box = new BlockBox(0, 0, 0, 1, 1, 1);
        assertTrue(box.contains(0.5, 0.5, 0.5));
        assertTrue(box.contains(0, 0, 0));
        assertFalse(box.contains(1.01, 0.5, 0.5));
        BlockBox expanded = box.expand(0.5);
        assertTrue(expanded.contains(1.4, 1.4, 1.4));
        assertFalse(expanded.contains(1.6, 0.5, 0.5));
    }

    @Test
    void emptyReplicaDefaultsToAir() {
        WorldReplica world = WorldReplica.empty();
        assertEquals(0, world.blockAt(0, 64, 0));
        assertFalse(world.hasBlockAt(0.5, 64.5, 0.5));
    }

    @Test
    void applyAndQueryBlockChange() {
        WorldReplica world = WorldReplica.empty();
        world.applyBlockChange(5, 64, 3, 1);
        assertEquals(1, world.blockAt(5, 64, 3));
        assertTrue(world.hasBlockAt(5.5, 64.2, 3.7));
        // 同列不同 y 互不干扰
        world.applyBlockChange(5, 65, 3, 7);
        assertEquals(7, world.blockAt(5, 65, 3));
        assertEquals(1, world.blockAt(5, 64, 3));
        // 覆盖为 air 即移除
        world.applyBlockChange(5, 64, 3, 0);
        assertEquals(0, world.blockAt(5, 64, 3));
        assertFalse(world.hasBlockAt(5.5, 64.2, 3.7));
    }

    @Test
    void negativeCoordinatesPackCorrectly() {
        WorldReplica world = WorldReplica.empty();
        world.applyBlockChange(-1, 70, -1, 4);
        world.applyBlockChange(-16, 70, -16, 5);
        assertEquals(4, world.blockAt(-1, 70, -1));
        assertEquals(5, world.blockAt(-16, 70, -16));
        // 方块 (-1,70,-1) 覆盖世界坐标 x,z ∈ [-1, 0)
        assertTrue(world.hasBlockAt(-0.5, 70.5, -0.5));
        assertFalse(world.hasBlockAt(-0.5, 70.5, 0.5));
        // 22 位 chunk 坐标边界内不互相踩踏
        world.applyBlockChange(33554416, 64, 0, 9); // chunkX = 2097151（22 位范围内）
        assertEquals(9, world.blockAt(33554416, 64, 0));
        assertEquals(4, world.blockAt(-1, 70, -1));
    }

    @Test
    void worldHeightCoversModernRange() {
        WorldReplica world = WorldReplica.empty();
        world.applyBlockChange(0, MinecraftVersion.MIN_Y, 0, 1);
        assertEquals(1, world.blockAt(0, MinecraftVersion.MIN_Y, 0));
        world.applyBlockChange(0, MinecraftVersion.MAX_Y, 0, 2);
        assertEquals(2, world.blockAt(0, MinecraftVersion.MAX_Y, 0));
        // 界外：低于 -64 / 高于 319
        world.applyBlockChange(0, MinecraftVersion.MIN_Y - 1, 0, 3);
        assertEquals(0, world.blockAt(0, MinecraftVersion.MIN_Y - 1, 0));
        world.applyBlockChange(0, MinecraftVersion.MAX_Y + 1, 0, 4);
        assertEquals(0, world.blockAt(0, MinecraftVersion.MAX_Y + 1, 0));
    }

    @Test
    void materialBlocksExposePhysics() {
        WorldReplica world = WorldReplica.empty();
        world.applyBlockChange(5, 64, 5, "SULFUR_BLOCK");
        assertEquals("SULFUR_BLOCK", world.materialAt(5, 64, 5));
        assertTrue(world.hasBlockAt(5.5, 64.5, 5.5));
        assertEquals(0.6, world.physicsAt(5, 64, 5).slipperiness(), 1e-9);

        world.applyBlockChange(6, 64, 5, "minecraft:ice");
        assertEquals(0.98, world.physicsAt(6, 64, 5).slipperiness(), 1e-9);

        world.applyBlockChange(7, 64, 5, "WATER");
        assertFalse(world.hasBlockAt(7.5, 64.5, 5.5), "水不是碰撞方块");
        assertEquals(null, world.materialAt(7, 64, 5));
    }

    @Test
    void blockPhysicsTableMapsKnownMaterials() {
        assertEquals(0.98, BlockPhysics.forMaterial("PACKED_ICE").slipperiness(), 1e-9);
        assertEquals(0.8, BlockPhysics.forMaterial("SLIME_BLOCK").bounciness(), 1e-9);
        assertEquals(0.8, BlockPhysics.forMaterial("HONEY_BLOCK").slipperiness(), 1e-9);
        assertEquals(BlockPhysics.SOLID, BlockPhysics.forMaterial("SOME_FUTURE_BLOCK"));
        assertFalse(BlockPhysics.forMaterial("AIR").collides());
        assertFalse(BlockPhysics.forMaterial("CAVE_AIR").collides());
    }

    @Test
    void minecraftVersionMatrixDeclares26x() {
        assertEquals(MinecraftVersion.V26_2, MinecraftVersion.parse("git-Paper-123 (MC: 26.2)"));
        assertEquals(MinecraftVersion.V26_1, MinecraftVersion.parse("26.1.1"));
        assertEquals(MinecraftVersion.V1_21, MinecraftVersion.parse("1.21.1"));
        assertEquals(MinecraftVersion.V1_20, MinecraftVersion.parse("1.20.4"));
        assertTrue(MinecraftVersion.V26_2.hasSulfurBlocks());
        assertFalse(MinecraftVersion.V26_1.hasSulfurBlocks());
        assertEquals(MinecraftVersion.V26_2, MinecraftVersion.latest());
    }
}
