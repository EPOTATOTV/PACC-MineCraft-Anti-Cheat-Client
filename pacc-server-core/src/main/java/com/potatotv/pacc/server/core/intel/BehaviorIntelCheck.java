package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.server.behavior.PatternCluster;
import com.potatotv.pacc.detection.server.behavior.StatOutlier;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PlayerPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 行为统计 intel 检查（技术文档 §7.4：统计离群 3σ + 模式聚类）。
 *
 * <p>单维度异常只产出低置信证据（不直接告警），跨维度印证交给
 * {@link IntelRuntime#fuse} 的融合管线。
 */
public final class BehaviorIntelCheck implements ServerCheck {

    private final IntelRuntime runtime;

    public BehaviorIntelCheck(IntelRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public CheckType type() {
        return CheckType.MOVE;
    }

    @Override
    public List<DetectionResult> onMove(PlayerPosition from, PlayerPosition to) {
        String playerId = to.playerId();
        double speed = to.pos().horizontalDistanceTo(from.pos());
        List<DetectionResult> results = new ArrayList<>();

        runtime.statOutlier().record(playerId, speed);
        Optional<Evidence> outlier = runtime.statOutlier().check(playerId);
        outlier.ifPresent(ev -> results.add(toResult(ev)));

        // 传真实朝向变化量：直线度启发式靠它区分「持续转向的正常行走」与「恒定朝向的机器人轨迹」，
        // 写死 0 会让任何持续直线移动都被判成 Baritone（合法玩家误报）。
        runtime.patternCluster().record(playerId, speed, yawDelta(from, to), false, false, to.timestampMs());
        Optional<Evidence> pattern = runtime.patternCluster().check(playerId);
        pattern.ifPresent(ev -> {
            results.add(toResult(ev));
            runtime.record(playerId, ev);
        });

        fuseInto(results, playerId);
        return results;
    }

    @Override
    public void reset(String playerId) {
        runtime.clear(playerId);
    }

    private void fuseInto(List<DetectionResult> results, String playerId) {
        runtime.fuse(playerId, type(), 0L, false)
                .ifPresent(results::add);
    }

    static DetectionResult toResult(Evidence evidence) {
        return DetectionResult.violation(
                CheckType.MOVE,
                evidence.detectorId(),
                (int) Math.round(evidence.confidence() * 100),
                evidence.detail(),
                evidence.timestampMs());
    }

    /**
     * 两个位置快照之间的朝向变化量（度，范围 [0,180]）。
     * 没有前一帧时返回 180，表示「无法判定为直线」——宁可打断直线累计，也不制造误报。
     */
    private static double yawDelta(PlayerPosition from, PlayerPosition to) {
        if (from == null) {
            return 180.0;
        }
        double delta = ((to.yaw() - from.yaw()) % 360.0 + 540.0) % 360.0 - 180.0;
        return Math.abs(delta);
    }
}
