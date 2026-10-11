package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交互检查：每刻放置速度（FAST_PLACE）与搭桥堆叠（SCAFFOLD）。
 */
public final class InteractionCheck implements ServerCheck {

    private static final long SCAFFOLD_WINDOW_MS = 100L;
    private static final int SCAFFOLD_STREAK_LIMIT = 3;

    private final ServerPluginConfig config;
    private final ConcurrentHashMap<String, long[]> placeTickState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlaceState> scaffoldState = new ConcurrentHashMap<>();

    public InteractionCheck(ServerPluginConfig config) {
        this.config = config;
    }

    @Override
    public CheckType type() {
        return CheckType.INTERACTION;
    }

    @Override
    public List<DetectionResult> onInteract(String playerId, Position pos, String action, long timestampMs) {
        if (playerId == null || !"PLACE".equals(action)) {
            return List.of();
        }
        List<DetectionResult> results = new ArrayList<>(2);

        long tick = timestampMs / 50;
        long[] state = placeTickState.compute(playerId, (k, current) -> {
            if (current == null || current[0] != tick) {
                return new long[]{tick, 1};
            }
            current[1]++;
            return current;
        });
        if (state[1] > config.maxPlacePerTick()) {
            results.add(DetectionResult.violation(CheckType.INTERACTION, "FAST_PLACE", 80,
                    "同一刻放置 " + state[1] + " 次，超过阈值 " + config.maxPlacePerTick(), timestampMs));
        }

        if (pos != null) {
            PlaceState previous = scaffoldState.get(playerId);
            int streak;
            if (previous != null && pos.y() < previous.lastY() && timestampMs - previous.lastTs < SCAFFOLD_WINDOW_MS) {
                streak = previous.streak() + 1;
            } else {
                streak = 1;
            }
            scaffoldState.put(playerId, new PlaceState(pos.y(), timestampMs, streak));
            if (streak >= SCAFFOLD_STREAK_LIMIT) {
                results.add(DetectionResult.violation(CheckType.INTERACTION, "SCAFFOLD", 75,
                        "100ms 内连续向下堆叠 " + streak + " 格", timestampMs));
            }
        }
        return results;
    }

    @Override
    public void reset(String playerId) {
        placeTickState.remove(playerId);
        scaffoldState.remove(playerId);
    }

    private record PlaceState(double lastY, long lastTs, int streak) {
    }
}
