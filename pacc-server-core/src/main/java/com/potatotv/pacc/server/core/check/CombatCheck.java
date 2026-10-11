package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗检查：攻击距离（REACH）、点击频率（AUTO_CLICKER）、跨目标切换与视角外命中（KILL_AURA）。
 */
public final class CombatCheck implements ServerCheck {

    private static final long CPS_WINDOW_MS = 1000L;
    private static final long TARGET_SWITCH_WINDOW_MS = 500L;
    private static final int TARGET_SWITCH_LIMIT = 4;
    private static final double OUT_OF_VIEW_ANGLE_DEG = 90.0;

    private final ServerPluginConfig config;
    private final ConcurrentHashMap<String, Deque<Long>> attackTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> targetSwitchTimes = new ConcurrentHashMap<>();

    public CombatCheck(ServerPluginConfig config) {
        this.config = config;
    }

    @Override
    public CheckType type() {
        return CheckType.COMBAT;
    }

    @Override
    public List<DetectionResult> onCombat(CombatEvent event) {
        if (event == null) {
            return List.of();
        }
        String attacker = event.attackerId();
        List<DetectionResult> results = new ArrayList<>(2);

        double reach = event.attackerPos().distanceTo(event.targetPos());
        if (reach > config.maxReachDistance()) {
            results.add(DetectionResult.violation(CheckType.COMBAT, "REACH", reachConfidence(reach),
                    String.format("攻击距离 %.2f 超过阈值 %.2f", reach, config.maxReachDistance()), event.timestampMs()));
        }

        int cps = recordAttack(attacker, event.timestampMs());
        if (cps > config.maxCps()) {
            results.add(DetectionResult.violation(CheckType.COMBAT, "AUTO_CLICKER", 85,
                    "1 秒内点击 " + cps + " 次，超过阈值 " + config.maxCps(), event.timestampMs()));
        }

        int switches = recordTargetSwitch(attacker, event.targetId(), event.timestampMs());
        if (switches > TARGET_SWITCH_LIMIT) {
            results.add(DetectionResult.violation(CheckType.COMBAT, "KILL_AURA", 85,
                    "500ms 内切换目标 " + switches + " 次", event.timestampMs()));
        }

        if (event.viewAngleDeg() > OUT_OF_VIEW_ANGLE_DEG) {
            results.add(DetectionResult.violation(CheckType.COMBAT, "KILL_AURA", 80,
                    String.format("视角外命中，夹角 %.1f°", event.viewAngleDeg()), event.timestampMs()));
        }
        return results;
    }

    @Override
    public void reset(String playerId) {
        attackTimes.remove(playerId);
        targetSwitchTimes.remove(playerId);
        lastTargets.remove(playerId);
    }

    private int recordAttack(String attacker, long ts) {
        Deque<Long> deque = attackTimes.computeIfAbsent(attacker, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(ts);
            while (!deque.isEmpty() && deque.peekFirst() < ts - CPS_WINDOW_MS) {
                deque.pollFirst();
            }
            return deque.size();
        }
    }

    private int recordTargetSwitch(String attacker, String targetId, long ts) {
        String previous = lastTargets.put(attacker, targetId);
        if (previous == null || previous.equals(targetId)) {
            return 0;
        }
        Deque<Long> deque = targetSwitchTimes.computeIfAbsent(attacker, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(ts);
            while (!deque.isEmpty() && deque.peekFirst() < ts - TARGET_SWITCH_WINDOW_MS) {
                deque.pollFirst();
            }
            return deque.size();
        }
    }

    private int reachConfidence(double reach) {
        double excess = (reach - config.maxReachDistance()) / config.maxReachDistance();
        return (int) Math.min(100, 60 + excess * 60);
    }
}
