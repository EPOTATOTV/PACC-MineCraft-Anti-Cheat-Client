package com.potatotv.pacc.geyser;

import com.potatotv.pacc.geyser.config.GeyserConfigLoader;
import com.potatotv.pacc.server.core.ServerPlatformAdapter;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerType;

import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Geyser 平台适配层。Geyser 跑在基岩客户端与 Java 服务端之间，一个 {@link GeyserConnection} 对应
 * 一条基岩连接。这里只做连接级的告警与断开，不涉及任何跨服封禁。
 *
 * <p>设备识别是 Geyser 侧相对 Java 插件多出来的一层：{@code connection.platform()} 给出客户端平台，
 * 据此调整阈值基线，避免触屏 / 手柄的正常输入被判成作弊。
 */
public final class GeyserPlatformAdapter implements ServerPlatformAdapter {

    /**
     * 断开连接的方法。Geyser 公开 API 未暴露 {@code disconnect}，但运行时对象（GeyserSession）有
     * {@code disconnect(String)}；用反射调用，拿不到就退化为只发消息。
     */
    private static final Method DISCONNECT_METHOD = resolveDisconnectMethod();

    private final GeyserApi api;
    private final GeyserConfigLoader config;
    private final Consumer<String> logger;

    public GeyserPlatformAdapter(GeyserApi api, GeyserConfigLoader config, Consumer<String> logger) {
        this.api = api;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public ServerType serverType() {
        return ServerType.GEYSER;
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
        // Geyser 公开 API 不暴露玩家坐标，位置类检测由后端服务端插件负责。
        return null;
    }

    @Override
    public GameMode getGameMode(String playerId) {
        return GameMode.SURVIVAL;
    }

    @Override
    public String getClientBrand(String playerId) {
        GeyserConnection connection = resolveConnection(playerId);
        return connection == null ? null : connection.version();
    }

    @Override
    public List<String> getInstalledMods(String playerId) {
        return List.of();
    }

    @Override
    public List<AddonInfo> getInstalledAddons(String playerId) {
        // Geyser 侧拿不到行为包清单，Addon 检测归玩家端或后端插件。
        return List.of();
    }

    /** 判定该连接的设备类型，用于阈值基线调整。 */
    public DeviceThresholds.DeviceClass deviceClass(String playerId) {
        GeyserConnection connection = resolveConnection(playerId);
        if (connection == null) {
            return DeviceThresholds.DeviceClass.UNKNOWN;
        }
        String platformName;
        try {
            platformName = connection.platform() == null ? null : connection.platform().name();
        } catch (RuntimeException ex) {
            platformName = null;
        }
        return DeviceThresholds.classify(platformName, connection.version());
    }

    @Override
    public void alert(String playerId, String message) {
        GeyserConnection connection = resolveConnection(playerId);
        if (connection != null) {
            connection.sendMessage("[PACC] " + message);
        }
    }

    /**
     * 处置。默认配置（处置级别 0）下引擎不会调到这台方法；运维显式开启后才走到这里。
     * Geyser 处于代理层，封禁会越出本服范围，故无论 KICK 还是 BAN，这里一律只断开当前这条连接，
     * 不做任何持久化或跨服封禁。
     */
    @Override
    public void punish(String playerId, PunishmentType type, String reason, long durationMs) {
        if (type == null || type == PunishmentType.ALERT) {
            return;
        }
        GeyserConnection connection = resolveConnection(playerId);
        if (connection == null) {
            log("玩家不在线，跳过处置：" + playerId + " " + type);
            return;
        }
        String detail = reason == null || reason.isBlank() ? "PACC 检测命中" : reason;
        if (type == PunishmentType.BAN || type == PunishmentType.TEMP_BAN) {
            log("Geyser 代理层不支持封禁（避免跨服），改为断开连接：" + playerId);
        }
        disconnect(connection, detail);
    }

    private void disconnect(GeyserConnection connection, String detail) {
        if (DISCONNECT_METHOD == null) {
            log("当前 Geyser 运行时未提供 disconnect，仅告警：" + connection.javaUuid());
            connection.sendMessage("[PACC] " + detail);
            return;
        }
        try {
            DISCONNECT_METHOD.invoke(connection, detail);
        } catch (ReflectiveOperationException ex) {
            log("断开连接失败：" + connection.javaUuid() + " " + ex.getMessage());
        }
    }

    private GeyserConnection resolveConnection(String playerId) {
        if (playerId == null || playerId.isBlank() || api == null) {
            return null;
        }
        try {
            return api.connectionByUuid(UUID.fromString(playerId));
        } catch (IllegalArgumentException ex) {
            // playerId 不是合法 UUID 时没有可用的连接。
            return null;
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }

    private static Method resolveDisconnectMethod() {
        try {
            return Class.forName("org.geysermc.geyser.session.GeyserSession")
                    .getMethod("disconnect", String.class);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
}
