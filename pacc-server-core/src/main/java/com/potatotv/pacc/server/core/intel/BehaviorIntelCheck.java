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

        runtime.patternCluster().record(playerId, speed, 0.0, false, false, to.timestampMs());
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
}
