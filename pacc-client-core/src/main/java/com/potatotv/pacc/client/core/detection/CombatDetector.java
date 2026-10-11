package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientCheckType;
import com.potatotv.pacc.client.core.model.ClientDetectionResult;
import com.potatotv.pacc.client.core.model.CombatSample;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 客户端战斗检测：攻击距离（REACH）、点击频率（AUTO_CLICKER）、跨目标切换与视角外命中（KILL_AURA）。
 *
 * <p>玩家端只观测本地玩家，因此历史窗口是单份的。
 */
public final class CombatDetector {

    private static final long CPS_WINDOW_MS = 1000L;
    private static final long TARGET_SWITCH_WINDOW_MS = 500L;
    private static final int TARGET_SWITCH_LIMIT = 4;
    private static final double OUT_OF_VIEW_ANGLE_DEG = 90.0;

    private final double maxReachDistance;
    private final int maxCps;

    private final Deque<Long> attackTimes = new ArrayDeque<>();
    private final Deque<Long> targetSwitchTimes = new ArrayDeque<>();
    private String lastTargetId;

    public CombatDetector() {
        this(3.1, 16);
    }

    public CombatDetector(double maxReachDistance, int maxCps) {
        this.maxReachDistance = maxReachDistance;
        this.maxCps = maxCps;
    }

    public List<ClientDetectionResult> onAttack(CombatSample sample) {
        if (sample == null) {
            return List.of();
        }
        List<ClientDetectionResult> results = new ArrayList<>(2);

        double reach = sample.reach();
        if (reach > maxReachDistance) {
            results.add(ClientDetectionResult.violation(ClientCheckType.COMBAT, "REACH", reachConfidence(reach),
                    String.format("攻击距离 %.2f 超过阈值 %.2f", reach, maxReachDistance), sample.timestampMs()));
        }

        int cps = recordAttack(sample.timestampMs());
        if (cps > maxCps) {
            results.add(ClientDetectionResult.violation(ClientCheckType.COMBAT, "AUTO_CLICKER", 85,
                    "1 秒内点击 " + cps + " 次，超过阈值 " + maxCps, sample.timestampMs()));
        }

        int switches = recordTargetSwitch(sample.targetId(), sample.timestampMs());
        if (switches > TARGET_SWITCH_LIMIT) {
            results.add(ClientDetectionResult.violation(ClientCheckType.COMBAT, "KILL_AURA", 85,
                    "500ms 内切换目标 " + switches + " 次", sample.timestampMs()));
        }

        if (sample.viewAngleDeg() > OUT_OF_VIEW_ANGLE_DEG) {
            results.add(ClientDetectionResult.violation(ClientCheckType.COMBAT, "KILL_AURA", 80,
                    String.format("视角外命中，夹角 %.1f°", sample.viewAngleDeg()), sample.timestampMs()));
        }
        return results;
    }

    /** 本地玩家换图 / 重生时重置历史窗口。 */
    public void reset() {
        attackTimes.clear();
        targetSwitchTimes.clear();
        lastTargetId = null;
    }

    private int recordAttack(long ts) {
        synchronized (attackTimes) {
            attackTimes.addLast(ts);
            while (!attackTimes.isEmpty() && attackTimes.peekFirst() < ts - CPS_WINDOW_MS) {
                attackTimes.pollFirst();
            }
            return attackTimes.size();
        }
    }

    private int recordTargetSwitch(String targetId, long ts) {
        String previous = lastTargetId;
        lastTargetId = targetId;
        if (previous == null || previous.equals(targetId)) {
            return 0;
        }
        synchronized (targetSwitchTimes) {
            targetSwitchTimes.addLast(ts);
            while (!targetSwitchTimes.isEmpty() && targetSwitchTimes.peekFirst() < ts - TARGET_SWITCH_WINDOW_MS) {
                targetSwitchTimes.pollFirst();
            }
            return targetSwitchTimes.size();
        }
    }

    private int reachConfidence(double reach) {
        double excess = (reach - maxReachDistance) / maxReachDistance;
        return (int) Math.min(100, 60 + excess * 60);
    }
}
