package com.potatotv.pacc.bedrock.listener;

import com.potatotv.pacc.bedrock.NukkitPlatformAdapter;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.level.Location;

/**
 * 移动事件监听：把 Nukkit 的移动事件转成内核的 {@link PlayerPosition} 快照后交给引擎。
 */
public final class NukkitMoveListener implements Listener {

    private static final long TICK_MS = 50L;

    private final DetectionEngine engine;

    public NukkitMoveListener(DetectionEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // 方块坐标没变（如仅转视角）时直接跳过，省掉后面的构造与检测开销。
        if (from.getFloorX() == to.getFloorX()
                && from.getFloorY() == to.getFloorY()
                && from.getFloorZ() == to.getFloorZ()) {
            return;
        }
        Player player = event.getPlayer();
        String playerId = NukkitPlatformAdapter.playerId(player);
        GameMode mode = NukkitPlatformAdapter.mapGameMode(player.getGamemode());
        boolean onGround = player.isOnGround();

        // 事件不提供历史时刻，用相邻两刻近似：to 取当前，from 取一刻之前。
        long now = System.currentTimeMillis();
        PlayerPosition fromPos = toPosition(playerId, from, onGround, mode, now - TICK_MS);
        PlayerPosition toPos = toPosition(playerId, to, onGround, mode, now);
        engine.handleMove(fromPos, toPos);
    }

    private static PlayerPosition toPosition(
            String playerId, Location loc, boolean onGround, GameMode mode, long timestampMs) {
        Position pos = new Position(loc.getX(), loc.getY(), loc.getZ());
        return new PlayerPosition(playerId, pos, (float) loc.getYaw(), (float) loc.getPitch(),
                timestampMs, onGround, mode);
    }
}
