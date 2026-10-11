package com.potatotv.pacc.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录每个玩家的客户端品牌与已注册信道（Java 版下近似“已装 Mod”信息）。
 * 通过 {@code minecraft:brand} 与 {@code minecraft:register} 入站信道采集。
 */
public final class ClientBrandTracker implements PluginMessageListener {

    private static final String BRAND_CHANNEL = "minecraft:brand";
    private static final String REGISTER_CHANNEL = "minecraft:register";

    private final ConcurrentHashMap<String, String> brands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> registeredChannels = new ConcurrentHashMap<>();

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (player == null) {
            return;
        }
        String playerId = player.getUniqueId().toString();
        try {
            if (BRAND_CHANNEL.equals(channel)) {
                String brand = DetectMath.decodeBrand(message);
                if (!brand.isBlank()) {
                    brands.put(playerId, brand);
                }
            } else if (REGISTER_CHANNEL.equals(channel)) {
                Set<String> channels = registeredChannels.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet());
                for (String name : new String(message, StandardCharsets.UTF_8).split("\u0000")) {
                    if (!name.isBlank()) {
                        channels.add(name);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 客户端可发任意畸形载荷，这里必须吞掉，绝不能因为入站数据打断服务端流程。
        }
    }

    public String brand(String playerId) {
        return brands.get(playerId);
    }

    public List<String> mods(String playerId) {
        Set<String> channels = registeredChannels.get(playerId);
        return channels == null ? List.of() : List.copyOf(channels);
    }

    public void clear(String playerId) {
        brands.remove(playerId);
        registeredChannels.remove(playerId);
    }
}
