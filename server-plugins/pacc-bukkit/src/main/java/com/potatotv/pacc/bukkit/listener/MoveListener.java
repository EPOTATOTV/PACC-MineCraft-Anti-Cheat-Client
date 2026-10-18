package com.potatotv.pacc.bukkit.listener;

import com.potatotv.pacc.bukkit.BukkitPlatformAdapter;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 移动事件监听：把 Bukkit 的移动事件转成内核的 {@link PlayerPosition} 快照后交给引擎。
 */
public final class MoveListener implements Listener {

    private static final long TICK_MS = 50L;

    private final DetectionEngine engine;

    public MoveListener(DetectionEngine engine) {
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
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        String playerId = player.getUniqueId().toString();
        GameMode mode = BukkitPlatformAdapter.mapGameMode(player.getGameMode());
        // Player#isOnGround 已废弃，取 Entity 上的等价实现。
        boolean onGround = ((Entity) player).isOnGround();

        // Bukkit 事件不提供历史时刻，用相邻两刻近似：to 取当前，from 取一刻之前。
        long now = System.currentTimeMillis();
        PlayerPosition fromPos = toPosition(playerId, from, onGround, mode, now - TICK_MS);
        PlayerPosition toPos = toPosition(playerId, to, onGround, mode, now);
        engine.handleMove(fromPos, toPos);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        // 传送会造成跳变位移，重置状态避免误判为 SPEED / FLY。
        engine.resetPlayer(event.getPlayer().getUniqueId().toString());
    }

    private static PlayerPosition toPosition(
            String playerId, Location loc, boolean onGround, GameMode mode, long timestampMs) {
        Position pos = new Position(loc.getX(), loc.getY(), loc.getZ());
        String world = loc.getWorld() == null ? "world" : loc.getWorld().getName();
        return new PlayerPosition(playerId, pos, loc.getYaw(), loc.getPitch(), timestampMs, onGround, mode,
                world);
    }
}
