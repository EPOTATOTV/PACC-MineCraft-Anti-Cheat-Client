package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;

import java.util.List;
import java.util.Map;

/**
 * 检查项。每个检查项有类型，只覆写自己关心的事件回调；默认回调返回空列表，表示不处理。
 *
 * <p>实现须线程安全：服务端插件是多线程的，同一实例会被多线程并发调用。
 */
public interface ServerCheck {

    CheckType type();

    default List<DetectionResult> onMove(PlayerPosition from, PlayerPosition to) {
        return List.of();
    }

    default List<DetectionResult> onCombat(CombatEvent event) {
        return List.of();
    }

    default List<DetectionResult> onPacket(PacketView packet) {
        return List.of();
    }

    default List<DetectionResult> onInventory(String playerId, String action, long timestampMs) {
        return List.of();
    }

    default List<DetectionResult> onInteract(String playerId, Position pos, String action, long timestampMs) {
        return List.of();
    }

    default List<DetectionResult> onClientBrand(String playerId, String brand) {
        return List.of();
    }

    /** Mod 列表检查；引擎会从适配层取已装 Mod 后路由到这里。 */
    default List<DetectionResult> onMods(String playerId, List<String> mods) {
        return List.of();
    }

    /** Mod 文件哈希检查；引擎会从适配层取 {@code pacc:mods} 上报的哈希后路由到这里。 */
    default List<DetectionResult> onModHashes(String playerId, List<String> hashes) {
        return List.of();
    }

    default List<DetectionResult> onAddons(String playerId, List<AddonInfo> addons) {
        return List.of();
    }

    /**
     * 方块被破坏（服务端认账的挖掘事件）。{@code blockType} 是平台给的方块材质名，
     * 如 {@code DEEPSLATE_DIAMOND_ORE}；平台拿不到时传 {@code null}。
     */
    default List<DetectionResult> onBlockBreak(
            String playerId, String blockType, Position pos, long timestampMs) {
        return List.of();
    }

    default List<DetectionResult> onDataConsistency(
            String playerId, Map<String, String> clientData, Map<String, String> serverData) {
        return List.of();
    }

    /** 玩家下线或重置状态时调用，清理该玩家的历史数据。 */
    default void reset(String playerId) {
        // 默认无状态
    }
}
