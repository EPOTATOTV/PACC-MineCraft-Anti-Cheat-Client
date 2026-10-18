package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.server.core.packet.BlockProbe;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Bukkit 方块通行性探针，给穿墙（Phase）检测用。
 *
 * <p>三态是刻意的：玩家不在线、世界取不到、区块没加载，这三种情况都返回
 * {@link SolidState#UNKNOWN}——「查不了」不能当成「可穿过」，否则区块边界上全是假阳性。
 *
 * <p>只在主线程查询：{@code World#getBlockAt} 在主线程之外访问区块数据不安全。数据包泵已经把
 * 检测转到主线程，这里再兜一道 {@link Bukkit#isPrimaryThread()}，被别处误调时宁可返回 UNKNOWN。
 */
public final class BukkitBlockProbe implements BlockProbe {

    @Override
    public SolidState stateAt(String playerId, int blockX, int blockY, int blockZ) {
        if (!Bukkit.isPrimaryThread()) {
            return SolidState.UNKNOWN;
        }
        Player player = BukkitPlatformAdapter.resolvePlayer(playerId);
        if (player == null) {
            return SolidState.UNKNOWN;
        }
        if (blockY < player.getWorld().getMinHeight() || blockY >= player.getWorld().getMaxHeight()) {
            return SolidState.UNKNOWN;
        }
        World world = player.getWorld();
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return SolidState.UNKNOWN;
        }
        try {
            Block block = world.getBlockAt(blockX, blockY, blockZ);
            return block.isPassable() ? SolidState.PASSABLE : SolidState.SOLID;
        } catch (RuntimeException ex) {
            // 世界正在卸载 / 被别的插件加锁：拿不到就说不确定，不猜
            return SolidState.UNKNOWN;
        }
    }
}
