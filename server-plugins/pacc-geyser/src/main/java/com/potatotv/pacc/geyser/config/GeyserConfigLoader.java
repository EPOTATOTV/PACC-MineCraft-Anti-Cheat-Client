package com.potatotv.pacc.geyser.config;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.ServerInfo;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@link SimpleYaml} 解析出的配置映射成内核配置对象。字段与 pacc-bukkit 的 {@code PluginConfigLoader}
 * 保持一致，缺失的键一律走默认值，保证旧配置也能正常启动。
 */
public final class GeyserConfigLoader {

    private final ServerPluginConfig serverPluginConfig;
    private final ServerInfo serverInfo;
    private final String ptvUrl;
    private final String ptvSecret;
    private final String ptvServerId;
    private final boolean deviceThresholdAdjust;

    public GeyserConfigLoader(Map<String, Object> cfg, String pluginVersion, String serverVersion,
                              String serverName, int maxPlayers, int serverPort) {
        Map<String, Object> root = cfg == null ? Map.of() : cfg;
        this.serverPluginConfig = buildConfig(root);
        String serverId = resolveServerId(root, serverName, serverPort);
        this.serverInfo = new ServerInfo(serverId, ServerType.GEYSER, pluginVersion, serverVersion, serverName, maxPlayers);
        Map<String, Object> ptv = subMap(root, "ptv");
        this.ptvUrl = str(ptv, "url", "");
        this.ptvSecret = str(ptv, "secret", "");
        String configuredPtvId = str(ptv, "server-id", "");
        this.ptvServerId = configuredPtvId.isBlank() ? serverId : configuredPtvId;
        this.deviceThresholdAdjust = bool(root, "device-threshold-adjust", true);
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

    public boolean deviceThresholdAdjust() {
        return deviceThresholdAdjust;
    }

    private static ServerPluginConfig buildConfig(Map<String, Object> root) {
        ServerPluginConfig.Builder builder = ServerPluginConfig.builder()
                .punishmentMode(intVal(root, "punishment-mode", 0))
                .confidenceThreshold(intVal(root, "confidence-threshold", 60))
                .maxReachDistance(doubleVal(root, "max-reach-distance", 3.1))
                .maxCps(intVal(root, "max-cps", 16))
                .maxHorizontalSpeedPerTick(doubleVal(root, "max-horizontal-speed-per-tick", 0.42))
                .maxPlacePerTick(intVal(root, "max-place-per-tick", 1))
                .flyAirborneTicksTrigger(intVal(root, "fly-airborne-ticks-trigger", 20));
        Set<CheckType> enabled = parseChecks(root.get("enabled-checks"));
        if (enabled != null) {
            builder.enabledChecks(enabled);
        }
        return builder.build();
    }

    /** 返回 null 表示配置未指定，沿用内核默认（全部启用）。 */
    private static Set<CheckType> parseChecks(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Set<CheckType> types = EnumSet.noneOf(CheckType.class);
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String name = String.valueOf(item).trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                types.add(CheckType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 未知检查项名直接跳过，不影响其余配置生效。
            }
        }
        return types;
    }

    private static String resolveServerId(Map<String, Object> root, String serverName, int serverPort) {
        String id = str(root, "server-id", "");
        if (!id.isBlank()) {
            return id;
        }
        return (serverName + ":" + serverPort).toLowerCase(Locale.ROOT);
    }

    private static String str(Map<String, Object> map, String key, String def) {
        Object value = map.get(key);
        return value == null ? def : String.valueOf(value);
    }

    private static int intVal(Map<String, Object> map, String key, int def) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static double doubleVal(Map<String, Object> map, String key, double def) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return def;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        return Map.of();
    }
}
