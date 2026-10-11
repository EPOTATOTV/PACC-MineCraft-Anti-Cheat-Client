package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.ServerInfo;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把 Bukkit 的 {@link FileConfiguration} 映射成内核配置对象。
 * 缺失的键一律走默认值，保证旧配置也能正常启动。
 */
public final class PluginConfigLoader {

    private final ServerPluginConfig serverPluginConfig;
    private final ServerInfo serverInfo;
    private final String ptvUrl;
    private final String ptvSecret;
    private final String ptvServerId;

    public PluginConfigLoader(FileConfiguration cfg, ServerType serverType, String pluginVersion,
                              String serverVersion, String serverName, int maxPlayers, int serverPort) {
        this.serverPluginConfig = buildConfig(cfg);
        String serverId = resolveServerId(cfg, serverName, serverPort);
        this.serverInfo = new ServerInfo(serverId, serverType, pluginVersion, serverVersion, serverName, maxPlayers);
        this.ptvUrl = stringOrDefault(cfg, "ptv.url", "");
        this.ptvSecret = stringOrDefault(cfg, "ptv.secret", "");
        String configuredPtvId = stringOrDefault(cfg, "ptv.server-id", "");
        this.ptvServerId = configuredPtvId.isBlank() ? serverId : configuredPtvId;
    }

    public ServerPluginConfig serverPluginConfig() {
        return serverPluginConfig;
    }

    public ServerInfo serverInfo() {
        return serverInfo;
    }

    public String ptvUrl() {
        return ptvUrl;
    }

    public String ptvSecret() {
        return ptvSecret;
    }

    public String ptvServerId() {
        return ptvServerId;
    }

    private static ServerPluginConfig buildConfig(FileConfiguration cfg) {
        ServerPluginConfig.Builder builder = ServerPluginConfig.builder()
                .punishmentMode(cfg.getInt("punishment-mode", 0))
                .confidenceThreshold(cfg.getInt("confidence-threshold", 60))
                .maxReachDistance(cfg.getDouble("max-reach-distance", 3.1))
                .maxCps(cfg.getInt("max-cps", 16))
                .maxHorizontalSpeedPerTick(cfg.getDouble("max-horizontal-speed-per-tick", 0.42))
                .maxPlacePerTick(cfg.getInt("max-place-per-tick", 1))
                .flyAirborneTicksTrigger(cfg.getInt("fly-airborne-ticks-trigger", 20));
        Set<CheckType> enabled = parseChecks(cfg.getStringList("enabled-checks"));
        if (enabled != null) {
            builder.enabledChecks(enabled);
        }
        return builder.build();
    }

    /** 返回 null 表示配置未指定，沿用内核默认（全部启用）。 */
    private static Set<CheckType> parseChecks(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        Set<CheckType> types = EnumSet.noneOf(CheckType.class);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                types.add(CheckType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 未知检查项名直接跳过，不影响其余配置生效。
            }
        }
        return types;
    }

    private static String resolveServerId(FileConfiguration cfg, String serverName, int serverPort) {
        String id = stringOrDefault(cfg, "server-id", "");
        if (!id.isBlank()) {
            return id;
        }
        return (serverName + ":" + serverPort).toLowerCase(Locale.ROOT);
    }

    private static String stringOrDefault(FileConfiguration cfg, String key, String def) {
        String value = cfg.getString(key, def);
        return value == null ? def : value;
    }
}
