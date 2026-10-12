package com.potatotv.pacc.bukkit.listener;

import com.potatotv.pacc.bukkit.ClientBrandTracker;
import com.potatotv.pacc.bukkit.ModHashTracker;
import com.potatotv.pacc.server.core.engine.DetectionEngine;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 连接事件监听：玩家退出时清空检测引擎与该玩家的品牌 / Mod 哈希追踪状态。
 */
public final class ConnectionListener implements Listener {

    private final DetectionEngine engine;
    private final ClientBrandTracker brandTracker;
    private final ModHashTracker modHashTracker;

    public ConnectionListener(DetectionEngine engine, ClientBrandTracker brandTracker,
                              ModHashTracker modHashTracker) {
        this.engine = engine;
        this.brandTracker = brandTracker;
        this.modHashTracker = modHashTracker;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String playerId = event.getPlayer().getUniqueId().toString();
        engine.resetPlayer(playerId);
        brandTracker.clear(playerId);
        if (modHashTracker != null) {
            modHashTracker.clear(playerId);
        }
    }
}
