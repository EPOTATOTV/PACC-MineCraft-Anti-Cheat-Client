package com.potatotv.pacc.detection.server.prediction;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 每玩家压缩世界副本（技术文档 §7.2 WorldReplica）。
 *
 * <p>文档口径：服务端为每名玩家维护一份按 palette 压缩的 chunk 副本
 * （单 chunk 16-64KB）。本实现用「packed key → blockId」稀疏表近似 palette 语义：
 * <ul>
 *   <li>未写入的坐标缺省为 air（blockId=0），等价于全 0 palette，天然零内存；</li>
 *   <li>写入过的坐标只存非 0 值，blockId 本身充当 palette 条目（全局数值 id）。</li>
 *   <li>只存被方块检测触碰的坐标，内存随交互量而非世界体积增长。</li>
 * </ul>
 *
 * <p>key 打包（64 位预算下的适配）：文档建议 {@code (chunkKey<<16)|packedLocalPos}，
 * 但 chunkKey 用标准 26 位 chunk 坐标（{@code (chunkX&0x3FFFFFF)<<38 | (chunkZ&0x3FFFFFF)}）
 * 需要 52 位，再左移 16 位放局部坐标共 68 位，超出 long。这里把 chunk 坐标截到 22 位
 * （±2M chunk ≈ ±3300 万格，覆盖任何实际存档），打包语义不变：
 * <pre>key = chunkKey(44 位) << 16 | packedLocalPos(16 位)</pre>
 * <pre>packedLocalPos = (x&amp;15)&lt;&lt;12 | (z&amp;15)&lt;&lt;8 | (y&amp;255)</pre>
 * y 用 8 位表达主世界 y∈[0,256)；y&lt;0 视为虚空、y≥256 视为界外，均无方块。
 *
 * <p>线程安全：检测在异步检测线程执行，内部用 {@link ConcurrentHashMap}。
 */
public final class WorldReplica {

    private static final long CHUNK_BITS = 0x3FFFFF; // 22 位
    private final ConcurrentHashMap<Long, Integer> blocks = new ConcurrentHashMap<>();

    private WorldReplica() {
    }

    /** 空副本：全图 air。 */
    public static WorldReplica empty() {
        return new WorldReplica();
    }

    /** 登记一次方块变更（服务端方块事件回放）。 */
    public void applyBlockChange(int x, int y, int z, int blockId) {
        if (y < 0 || y > 255) {
            return; // 副本只覆盖主世界 y∈[0,256)，界外无碰撞意义
        }
        long key = packKey(x, y, z);
        if (blockId == 0) {
            blocks.remove(key);
        } else {
            blocks.put(key, blockId);
        }
    }

    /** 查询方块 id；无记录 / 虚空 / 界外返回 0（air）。 */
    public int blockAt(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return 0;
        }
        return blocks.getOrDefault(packKey(x, y, z), 0);
    }

    /**
     * 世界坐标点是否处于固体方块内（blockId != 0 视为固体；y&lt;0 视为虚空，不挡碰撞）。
     * 预测引擎用它过滤「穿进固体」的候选位置。
     */
    public boolean hasBlockAt(double x, double y, double z) {
        if (y < 0) {
            return false;
        }
        return blockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) != 0;
    }

    /** 已登记的非空方块数（诊断用）。 */
    public int size() {
        return blocks.size();
    }

    static long packKey(int x, int y, int z) {
        long chunkX = x >> 4;
        long chunkZ = z >> 4;
        long chunkKey = ((chunkX & CHUNK_BITS) << 22) | (chunkZ & CHUNK_BITS);
        int packedLocal = ((x & 15) << 12) | ((z & 15) << 8) | (y & 0xFF);
        return (chunkKey << 16) | packedLocal;
    }
}
