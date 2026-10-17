package com.potatotv.pacc.detection.server.mining;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端矿石发现率统计（技术文档 §5.3 U-04 / §7.4）。
 *
 * <p>只统计服务端**认账**的挖掘：方块破坏事件由服务端发出，客户端伪造不了。
 * 每个玩家维护矿石总数、稀有矿数与水平移动距离，交给 {@link XRayAnalyzer} 判比值。
 *
 * <p>移动距离按「相邻两次位置的水平位移」累加，并丢弃单次超过
 * {@value #MAX_SINGLE_MOVE_BLOCKS} 格的跳变——传送、回城、载具瞬移不是探索路径，
 * 计进去会把分母撑大，反而让 X-Ray 玩家看起来正常。
 *
 * <p>线程安全：每玩家一个 {@code synchronized} 计数器，事件来自主线程与包线程。
 */
public final class OreDiscoveryStats {

    /** 单次移动计入探索路径的上限（格）；超过视为传送/瞬移，不计入。 */
    public static final double MAX_SINGLE_MOVE_BLOCKS = 32.0;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    /** 记录挖到一块矿石；非矿石方块直接忽略。 */
    public void recordOre(String playerId, String blockType) {
        OreType type = OreType.fromBlock(blockType);
        if (type == null) {
            return;
        }
        Counter counter = counters.computeIfAbsent(playerId, unused -> new Counter());
        counter.recordOre(type);
    }

    /** 记录一次水平移动（格）。 */
    public void recordTravel(String playerId, double horizontalDistance) {
        if (horizontalDistance <= 0 || horizontalDistance > MAX_SINGLE_MOVE_BLOCKS) {
            return;
        }
        counters.computeIfAbsent(playerId, unused -> new Counter()).recordTravel(horizontalDistance);
    }

    /** 取玩家当前画像快照；没有记录时返回全零。 */
    public OreProfile profile(String playerId) {
        Counter counter = counters.get(playerId);
        return counter == null ? new OreProfile(0, 0, 0) : counter.snapshot();
    }

    /** 会话结束或重连时清空，避免把上一局的数据算进这一局。 */
    public void reset(String playerId) {
        counters.remove(playerId);
    }

    /** 单玩家计数器。 */
    private static final class Counter {

        private int totalOres;
        private int rareOres;
        private double travelledBlocks;

        private synchronized void recordOre(OreType type) {
            totalOres++;
            if (type.rare()) {
                rareOres++;
            }
        }

        private synchronized void recordTravel(double distance) {
            travelledBlocks += distance;
        }

        private synchronized OreProfile snapshot() {
            return new OreProfile(totalOres, rareOres, travelledBlocks);
        }
    }
}
