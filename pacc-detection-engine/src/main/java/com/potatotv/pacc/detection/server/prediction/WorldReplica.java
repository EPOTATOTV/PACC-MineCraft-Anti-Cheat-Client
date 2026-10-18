package com.potatotv.pacc.detection.server.prediction;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 每玩家压缩世界副本（技术文档 §7.2 WorldReplica）。
 *
 * <p>文档口径：服务端为每名玩家维护一份按 palette 压缩的 chunk 副本
 * （单 chunk 16-64KB）。本实现用「packed key → 方块状态」稀疏表近似 palette 语义：
 * <ul>
 *   <li>未写入的坐标缺省为 air，等价于全 0 palette，天然零内存；</li>
 *   <li>写入过的坐标只存非空气方块，方块 id 与材质名二选一充当 palette 条目；</li>
 *   <li>只存被方块检测触碰的坐标，内存随交互量而非世界体积增长。</li>
 * </ul>
 *
 * <p>P-01 适配 1.18+ / 26.x 世界高度：y 覆盖 {@link MinecraftVersion#MIN_Y}..{@link MinecraftVersion#MAX_Y}
 * （-64..319），不再把 y&lt;0 当虚空。key 打包为：
 * <pre>key = chunkKey(44 位) &lt;&lt; 17 | packedLocalPos(17 位)</pre>
 * <pre>packedLocalPos = (x&amp;15)&lt;&lt;13 | (z&amp;15)&lt;&lt;9 | ((y+64)&amp;0x1FF)</pre>
 * chunk 坐标仍截到 22 位（±2M chunk），44 + 17 = 61 位，未溢出 long。
 *
 * <p>方块材质交给 {@link BlockPhysics} 查表，黏液块、冰面、蜂蜜块与 26.2 硫磺方块
 * 不再被当成统一滑度 0.6 的固体。
 *
 * <p>线程安全：检测在异步检测线程执行，内部用 {@link ConcurrentHashMap}。
 */
public final class WorldReplica {

    private static final long CHUNK_BITS = 0x3FFFFF; // 22 位
    private static final int Y_BITS = 0x1FF;         // 9 位，覆盖 -64..319
    private static final int Y_OFFSET = -MinecraftVersion.MIN_Y;
    private final ConcurrentHashMap<Long, BlockState> blocks = new ConcurrentHashMap<>();

    private WorldReplica() {
    }

    /** 空副本：全图 air。 */
    public static WorldReplica empty() {
        return new WorldReplica();
    }

    /** 登记一次方块变更（数值 id 口径，兼容既有调用方）。 */
    public void applyBlockChange(int x, int y, int z, int blockId) {
        if (!inRange(y)) {
            return;
        }
        long key = packKey(x, y, z);
        if (blockId == 0) {
            blocks.remove(key);
        } else {
            blocks.put(key, new BlockState(blockId, null));
        }
    }

    /** 登记一次方块变更（材质口径）。空气材质等同于移除方块。 */
    public void applyBlockChange(int x, int y, int z, String material) {
        if (!inRange(y)) {
            return;
        }
        long key = packKey(x, y, z);
        BlockPhysics physics = BlockPhysics.forMaterial(material);
        if (material == null || material.isBlank() || !physics.collides()) {
            blocks.remove(key);
        } else {
            blocks.put(key, new BlockState(1, material));
        }
    }

    /** 查询方块 id；无记录 / 界外返回 0（air）。 */
    public int blockAt(int x, int y, int z) {
        if (!inRange(y)) {
            return 0;
        }
        BlockState state = blocks.get(packKey(x, y, z));
        return state == null ? 0 : state.id();
    }

    /** 查询方块材质；数值 id 登记的方块返回 {@code null}。 */
    public String materialAt(int x, int y, int z) {
        if (!inRange(y)) {
            return null;
        }
        BlockState state = blocks.get(packKey(x, y, z));
        return state == null ? null : state.material();
    }

    /** 查询方块物理属性；未登记为空气。 */
    public BlockPhysics physicsAt(int x, int y, int z) {
        if (!inRange(y)) {
            return BlockPhysics.AIR;
        }
        BlockState state = blocks.get(packKey(x, y, z));
        if (state == null) {
            return BlockPhysics.AIR;
        }
        if (state.material() != null) {
            return BlockPhysics.forMaterial(state.material());
        }
        return BlockPhysics.SOLID;
    }

    /**
     * 世界坐标点是否处于可碰撞方块内。预测引擎用它过滤「穿进固体」的候选位置。
     */
    public boolean hasBlockAt(double x, double y, double z) {
        if (!inRange((int) Math.floor(y))) {
            return false;
        }
        return physicsAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).collides();
    }

    /** 已登记的非空方块数（诊断用）。 */
    public int size() {
        return blocks.size();
    }

    static boolean inRange(int y) {
        return y >= MinecraftVersion.MIN_Y && y <= MinecraftVersion.MAX_Y;
    }

    static long packKey(int x, int y, int z) {
        long chunkX = x >> 4;
        long chunkZ = z >> 4;
        long chunkKey = ((chunkX & CHUNK_BITS) << 22) | (chunkZ & CHUNK_BITS);
        int packedLocal = ((x & 15) << 13) | ((z & 15) << 9) | ((y + Y_OFFSET) & Y_BITS);
        return (chunkKey << 17) | packedLocal;
    }

    /** 方块状态：数值 id 与材质二选一，避免为数值口径额外分配字符串。 */
    private record BlockState(int id, String material) {
    }
}
