package com.potatotv.pacc.bedrock;

import com.potatotv.pacc.server.core.ServerPlatformAdapter;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerType;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.level.Location;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.utils.LoginChainData;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Nukkit / Nukkit-MOT 平台适配层。两者的 {@code cn.nukkit} API 同源，这里共用一份实现；
 * 运行的是哪一个由构造时传入的 {@link ServerType} 决定。所有 API 调用都在服务端主线程完成。
 */
public final class NukkitPlatformAdapter implements ServerPlatformAdapter {

    private final Plugin plugin;
    private final NukkitConfigLoader config;
    private final ServerType serverType;

    public NukkitPlatformAdapter(Plugin plugin, NukkitConfigLoader config, ServerType serverType) {
        this.plugin = plugin;
        this.config = config;
        this.serverType = serverType;
    }

    /**
     * 玩家在检测引擎里的稳定标识。基岩版以登录链里的客户端 UUID 为准；离线或链信息缺失时退回名字。
     * 引擎只把它当作状态键与上报载荷，两种来源都不跨服务器。
     */
    public static String playerId(Player player) {
        LoginChainData chain = player.getLoginChainData();
        UUID uuid = chain == null ? null : chain.getClientUUID();
        return uuid == null ? player.getName() : uuid.toString();
    }

    /** Nukkit 的 gamemode 用 int 表示：0=生存，1=创造，2=冒险，3=旁观。 */
    public static GameMode mapGameMode(int gamemode) {
        return switch (gamemode) {
            case Player.CREATIVE -> GameMode.CREATIVE;
            case Player.ADVENTURE -> GameMode.ADVENTURE;
            case Player.SPECTATOR -> GameMode.SPECTATOR;
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
        return new PlayerPosition(playerId, pos, (float) loc.getYaw(), (float) loc.getPitch(),
                System.currentTimeMillis(), player.isOnGround(), mapGameMode(player.getGamemode()));
    }

    @Override
    public GameMode getGameMode(String playerId) {
        Player player = resolvePlayer(playerId);
        return player == null ? GameMode.SURVIVAL : mapGameMode(player.getGamemode());
    }

    /**
     * 基岩版没有 Java 版的 brand 通道，客户端信息取自登录链数据。这里用设备型号作为品牌；
     * 型号缺失时退回设备操作系统编号，避免返回空串让品牌检查完全失效。
     */
    @Override
    public String getClientBrand(String playerId) {
        Player player = resolvePlayer(playerId);
        if (player == null) {
            return "";
        }
        LoginChainData chain = player.getLoginChainData();
        if (chain == null) {
            return "";
        }
        String model = chain.getDeviceModel();
        if (model != null && !model.isBlank()) {
            return model;
        }
        return "os-" + chain.getDeviceOS();
    }

    @Override
    public List<String> getInstalledMods(String playerId) {
        // 基岩版客户端没有可供服务端枚举的 Mod 列表。
        return List.of();
    }

    @Override
    public List<AddonInfo> getInstalledAddons(String playerId) {
        // 目前没有稳定的服务端 API 能枚举玩家端已加载的行为包 / Addon，返回空列表。
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
     * 设为 1 / 2 才会走到这里。KICK 直接踢出；BAN / TEMP_BAN 走 Nukkit 的名称封禁表（时长 &le; 0 为永久），
     * 只作用于**本服务器**。玩家不在线时只记日志，不做跨请求的补偿，也不对接任何第三方封禁系统。
     */
    @Override
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
                player.kick(detail);
                return;
            }
            Date expires = banExpiry(durationMs, System.currentTimeMillis());
            Server.getInstance().getNameBans()
                    .addBan(player.getName(), detail, expires, plugin.getDescription().getName());
            player.kick(detail);
        } catch (RuntimeException ex) {
            // 处置失败绝不冒泡到主线程事件分发里。
            plugin.getLogger().warning("执行处置失败：" + playerId + " " + type + " " + ex.getMessage());
        }
    }

    /** 封禁到期时间；{@code durationMs <= 0} 表示永久封禁，返回 null（Nukkit 以此为永久）。 */
    static Date banExpiry(long durationMs, long nowMs) {
        return durationMs > 0 ? new Date(nowMs + durationMs) : null;
    }

    private static Player resolvePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        Player byName = Server.getInstance().getPlayerExact(playerId);
        if (byName != null) {
            return byName;
        }
        for (Player player : Server.getInstance().getOnlinePlayers().values()) {
            if (playerId.equalsIgnoreCase(playerId(player))) {
                return player;
            }
        }
        return null;
    }
}
