package com.potatotv.pacc.bukkit.listener;

import com.potatotv.pacc.server.core.engine.DetectionEngine;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 方块放置监听（P-01）：把放置材质喂进共享世界副本。
 *
 * <p>预测引擎需要知道脚下 / 周围方块的真实材质才能应用滑度、反弹与碰撞。
 * 只在服务端认可的放置事件上更新，客户端伪造的放置包不会进世界副本。
 */
public final class BlockPlaceListener implements Listener {

    private final DetectionEngine engine;

    public BlockPlaceListener(DetectionEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        engine.handleBlockChanged(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                block.getType().name());
    }
}
