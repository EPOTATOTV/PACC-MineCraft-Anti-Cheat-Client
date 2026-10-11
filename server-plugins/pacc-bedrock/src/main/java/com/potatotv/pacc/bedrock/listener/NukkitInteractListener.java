package com.potatotv.pacc.bedrock.listener;

import com.potatotv.pacc.bedrock.NukkitPlatformAdapter;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.Position;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerInteractEvent;

/**
 * 交互事件监听：右键点击方块视为放置，喂给内核的交互检查（FAST_PLACE / SCAFFOLD）。
 */
public final class NukkitInteractListener implements Listener {

    private final DetectionEngine engine;

    public NukkitInteractListener(DetectionEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        Position pos = new Position(block.getX(), block.getY(), block.getZ());
        engine.handleInteract(NukkitPlatformAdapter.playerId(player), pos, "PLACE", System.currentTimeMillis());
    }
}
