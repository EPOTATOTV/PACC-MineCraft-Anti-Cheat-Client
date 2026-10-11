package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 背包检查：界面开启时移动/攻击（INVENTORY_MOVE）、进食过快（FAST_EAT）、图腾切换过快（AUTO_TOTEM）。
 * 判定依赖上游给出的动作字符串约定，本检查只做时间间隔与动作判定。
 */
public final class InventoryCheck implements ServerCheck {

    private static final long FAST_EAT_MIN_INTERVAL_MS = 800L;
    private static final long AUTO_TOTEM_MIN_INTERVAL_MS = 500L;

    private final ConcurrentHashMap<String, Long> lastEat = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTotemSwap = new ConcurrentHashMap<>();

    @Override
    public CheckType type() {
        return CheckType.INVENTORY;
    }

    @Override
    public List<DetectionResult> onInventory(String playerId, String action, long timestampMs) {
        if (playerId == null || action == null) {
            return List.of();
        }
        return switch (action) {
            case "MOVE_WHILE_OPEN", "ATTACK_WHILE_OPEN", "CLICK_OUTSIDE" -> List.of(
                    DetectionResult.violation(CheckType.INVENTORY, "INVENTORY_MOVE", 80,
                            "界面开启期间出现 " + action, timestampMs));
            case "FAST_EAT" -> checkInterval(
                    lastEat, playerId, timestampMs, FAST_EAT_MIN_INTERVAL_MS, "FAST_EAT", "进食间隔过短");
            case "TOTEM_SWAP" -> checkInterval(
                    lastTotemSwap, playerId, timestampMs, AUTO_TOTEM_MIN_INTERVAL_MS, "AUTO_TOTEM", "图腾切换间隔过短");
            default -> List.of();
        };
    }

    @Override
    public void reset(String playerId) {
        lastEat.remove(playerId);
        lastTotemSwap.remove(playerId);
    }

    private static List<DetectionResult> checkInterval(
            ConcurrentHashMap<String, Long> state, String playerId, long ts, long minIntervalMs,
            String cheatType, String detail) {
        Long previous = state.put(playerId, ts);
        if (previous != null && ts - previous < minIntervalMs) {
            return List.of(DetectionResult.violation(CheckType.INVENTORY, cheatType, 85,
                    detail + "（间隔 " + (ts - previous) + "ms）", ts));
        }
        return List.of();
    }
}
