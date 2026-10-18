package com.potatotv.pacc.bukkit.listener;

import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.Position;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * 方块破坏监听：把服务端认账的挖掘事件喂给引擎（X-Ray 统计的入口）。
 *
 * <p>为什么走事件而不是解析 {@code PLAYER_DIGGING} 包：包只给坐标，不给方块**种类**；
 * 等包处理完再去世界里查，方块往往已经是空气了。{@link BlockBreakEvent} 在方块被移除前触发，
 * 材质还读得到，而且天然只包含服务端认可的挖掘。
 *
 * <p>创造模式跳过：创造玩家本来就秒破方块，计入统计只会污染分布。
 */
public final class BlockBreakListener implements Listener {

    private final DetectionEngine engine;

    public BlockBreakListener(DetectionEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Block block = event.getBlock();
        // P-01：破坏后该格变空气，先把世界副本更新掉，再做 X-Ray 统计
        engine.handleBlockChanged(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                "AIR");
        Position pos = new Position(block.getX(), block.getY(), block.getZ());
        engine.handleBlockBreak(player.getUniqueId().toString(), block.getType().name(),
                pos, System.currentTimeMillis());
    }
}
