package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.server.core.protocol.ModHashParser;
import com.potatotv.pacc.server.core.protocol.ModHashReport;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录每个玩家通过 {@code pacc:mods} 通道上报的 Mod 文件哈希（小写 sha256）。
 * 通道名、载荷格式与约束见 {@link ModHashParser}；客户端发送端由 {@code pacc-forge-mod} /
 * {@code pacc-fabric-mod} 实现。同一玩家重复上报时以最新一次为准。
 */
public final class ModHashTracker implements PluginMessageListener {

    private final ConcurrentHashMap<String, List<String>> hashes = new ConcurrentHashMap<>();

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (player == null) {
            return;
        }
        try {
            Optional<ModHashReport> report = ModHashParser.parse(message);
            if (report.isEmpty()) {
                // 畸形 / 超限载荷已在解析器内记日志并丢弃，这里不做额外处理。
                return;
            }
            String playerId = player.getUniqueId().toString();
            hashes.put(playerId, List.copyOf(report.get().hashes()));
        } catch (RuntimeException ignored) {
            // 客户端可发任意畸形载荷，这里必须吞掉，绝不能因为入站数据打断服务端流程。
        }
    }

    public List<String> hashes(String playerId) {
        List<String> cached = hashes.get(playerId);
        return cached == null ? List.of() : cached;
    }

    public void clear(String playerId) {
        hashes.remove(playerId);
    }
}
