package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.server.core.ServerPlatformAdapter;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerType;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Bukkit 平台适配层。所有 Bukkit API 调用都必须在主线程执行，检测事件本身就在主线程分发，故无需额外调度。
 */
public final class BukkitPlatformAdapter implements ServerPlatformAdapter {

    /** Paper 专有类，用来区分 Paper 与 Spigot。 */
    private static final String PAPER_CONFIG_CLASS = "io.papermc.paper.configuration.Configuration";

    /** Paper 的 {@code Player#getClientBrandName()}，Spigot 上不存在，故走反射。 */
    private static final Method CLIENT_BRAND_METHOD = resolveClientBrandMethod();

    private final Plugin plugin;
    private final PluginConfigLoader config;
    private final ClientBrandTracker brandTracker;
    private final ServerType serverType;

    public BukkitPlatformAdapter(Plugin plugin, PluginConfigLoader config, ClientBrandTracker brandTracker) {
        this.plugin = plugin;
        this.config = config;
        this.brandTracker = brandTracker;
        this.serverType = detectServerType();
    }

    /** 存在 Paper 专有配置类即为 PAPER，否则按 SPIGOT 处理。 */
    public static ServerType detectServerType() {
        try {
            Class.forName(PAPER_CONFIG_CLASS, false, BukkitPlatformAdapter.class.getClassLoader());
            return ServerType.PAPER;
        } catch (ClassNotFoundException ex) {
            return ServerType.SPIGOT;
        }
    }

    public static GameMode mapGameMode(org.bukkit.GameMode mode) {
        if (mode == null) {
            return GameMode.SURVIVAL;
        }
        return switch (mode) {
            case CREATIVE -> GameMode.CREATIVE;
            case ADVENTURE -> GameMode.ADVENTURE;
            case SPECTATOR -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
    }

    @Override
    public ServerType serverType() {
        return serverType;
    }

    @Override
    public String serverId() {
        return config.serverInfo().serverId();
    }

    @Override
    public String serverName() {
        return config.serverInfo().serverName();
    }

    @Override
    public int maxPlayers() {
        return config.serverInfo().maxPlayers();
    }

    @Override
    public String serverVersion() {
        return config.serverInfo().serverVersion();
    }

    @Override
    public PlayerPosition getPlayerPosition(String playerId) {
        Player player = resolvePlayer(playerId);
        if (player == null) {
            return null;
        }
        Location loc = player.getLocation();
        Position pos = new Position(loc.getX(), loc.getY(), loc.getZ());
        return new PlayerPosition(playerId, pos, loc.getYaw(), loc.getPitch(),
                System.currentTimeMillis(), player.isOnGround(), getGameMode(playerId));
    }

    @Override
    public GameMode getGameMode(String playerId) {
        Player player = resolvePlayer(playerId);
        return player == null ? GameMode.SURVIVAL : mapGameMode(player.getGameMode());
    }

    @Override
    public String getClientBrand(String playerId) {
        Player player = resolvePlayer(playerId);
        if (player != null && CLIENT_BRAND_METHOD != null) {
            try {
                Object value = CLIENT_BRAND_METHOD.invoke(player);
                if (value instanceof String brand && !brand.isBlank()) {
                    return brand;
                }
            } catch (ReflectiveOperationException ignored) {
                // 反射不可用时退回 tracker 缓存。
            }
        }
        return brandTracker.brand(playerId);
    }

    @Override
    public List<String> getInstalledMods(String playerId) {
        return brandTracker.mods(playerId);
    }

    @Override
    public List<AddonInfo> getInstalledAddons(String playerId) {
        // Java 版没有行为包 / Addon 概念。
        return List.of();
    }

    @Override
    public void alert(String playerId, String message) {
        Player player = resolvePlayer(playerId);
        if (player != null) {
            player.sendMessage("[PACC] " + message);
        }
    }

    /**
     * 执行处置。默认配置（处置级别 0）下引擎不会调到这台方法，只有运维显式把 {@code punishment-mode}
     * 设为 1 / 2 才会走到这里。踢出与封禁都只作用于**本服务器**：KICK 直接踢出，BAN / TEMP_BAN 走
     * Bukkit 的名称封禁表（时长 &le; 0 为永久）。玩家不在线时只记日志，不做跨请求的补偿。
     */
    @Override
    @SuppressWarnings("deprecation") // kickPlayer(String) 在 Paper 上已废弃，但它是 Spigot/Paper 通吃的进服踢出入口。
    public void punish(String playerId, PunishmentType type, String reason, long durationMs) {
        if (type == null || type == PunishmentType.ALERT) {
            return; // 仅告警由 alert() 负责，这里不做事。
        }
        Player player = resolvePlayer(playerId);
        if (player == null) {
            plugin.getLogger().warning("玩家不在线，跳过处置：" + playerId + " " + type);
            return;
        }
        String detail = reason == null || reason.isBlank() ? "PACC 检测命中" : reason;
        try {
            if (type == PunishmentType.KICK) {
                player.kickPlayer(detail);
                return;
            }
            Date expires = banExpiry(durationMs, System.currentTimeMillis());
            Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(), detail, expires, plugin.getName());
            player.kickPlayer(detail);
        } catch (RuntimeException ex) {
            // 处置失败绝不冒泡到主线程事件分发里。
            plugin.getLogger().warning("执行处置失败：" + playerId + " " + type + " " + ex.getMessage());
        }
    }

    /** 封禁到期时间；{@code durationMs <= 0} 表示永久封禁，返回 null（Bukkit 以此为永久）。 */
    static Date banExpiry(long durationMs, long nowMs) {
        return durationMs > 0 ? new Date(nowMs + durationMs) : null;
    }

    private static Player resolvePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        Player byName = Bukkit.getPlayerExact(playerId);
        if (byName != null) {
            return byName;
        }
        try {
            return Bukkit.getPlayer(UUID.fromString(playerId));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Method resolveClientBrandMethod() {
        try {
            return Player.class.getMethod("getClientBrandName");
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
