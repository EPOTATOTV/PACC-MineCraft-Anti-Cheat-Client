package com.potatotv.pacc.bedrock.listener;

import com.potatotv.pacc.bedrock.NukkitPlatformAdapter;
import com.potatotv.pacc.server.core.engine.DetectionEngine;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;

/**
 * 连接事件监听：进服时采集客户端标识与 Addon 信息，退服时清空该玩家的历史状态。
 */
public final class NukkitConnectionListener implements Listener {

    private final DetectionEngine engine;
    private final NukkitPlatformAdapter adapter;

    public NukkitConnectionListener(DetectionEngine engine, NukkitPlatformAdapter adapter) {
        this.engine = engine;
        this.adapter = adapter;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerId = NukkitPlatformAdapter.playerId(player);
        engine.handleClientBrand(playerId, adapter.getClientBrand(playerId));
        engine.handleAddons(playerId, adapter.getInstalledAddons(playerId));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        engine.resetPlayer(NukkitPlatformAdapter.playerId(event.getPlayer()));
    }
}
