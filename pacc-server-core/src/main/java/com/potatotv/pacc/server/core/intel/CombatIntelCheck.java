package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.profile.RotationSample;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 战斗 intel 检查（技术文档 §7.3：Reach 射线校验、攻击时序、旋转分析、击退预测）。
 *
 * <p>自动接入 CombatEvent 就能拿到证据的通道：Reach（3.01 格阈值）与攻击时序
 * （KillAura/MultiAura）。需要平台适配层额外喂数据的通道（旋转流、击退事件、
 * 视线命中箱）通过访问器暴露，由插件在各自监听器里调用。
 */
public final class CombatIntelCheck implements ServerCheck {

    private final IntelRuntime runtime;

    public CombatIntelCheck(IntelRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public CheckType type() {
        return CheckType.COMBAT;
    }

    @Override
    public List<DetectionResult> onCombat(CombatEvent event) {
        List<DetectionResult> results = new ArrayList<>();
        String attacker = event.attackerId();

        runtime.reachCheck().check(
                        attacker, IntelRuntime.toEnginePos(event.attackerPos()),
                        IntelRuntime.toEnginePos(event.targetPos()), true, event.timestampMs())
                .ifPresent(ev -> {
                    results.add(toResult(ev));
                    runtime.record(attacker, ev);
                });

        runtime.attackPattern().recordAttack(attacker, event.timestampMs(), event.targetId());
        runtime.attackPattern().check(attacker).ifPresent(ev -> {
            results.add(toResult(ev));
            runtime.record(attacker, ev);
        });

        runtime.fuse(attacker, type(), 0L, false).ifPresent(results::add);
        return results;
    }

    @Override
    public void reset(String playerId) {
        runtime.clear(playerId);
    }

    /** 旋转流入口：平台监听器把玩家视角变化喂进来（attacking=是否处于攻击后短窗口）。 */
    public void recordRotation(String playerId, float yaw, float pitch, long timestampMs, boolean attacking) {
        runtime.rotationAnalyzer().record(playerId, new RotationSample(yaw, pitch, timestampMs, attacking));
    }

    /** 旋转分析入口：攒够窗口后调用，返回 KillAura 旋转特征证据。 */
    public Optional<DetectionResult> checkRotation(String playerId) {
        return runtime.rotationAnalyzer().check(playerId).map(ev -> {
            runtime.record(playerId, ev);
            return toResult(ev);
        });
    }

    /** 击退事件入口：服务端施加击退时调用（§7.3 击退预测）。 */
    public void recordKnockback(String playerId, Position posBefore, double strength, long timestampMs) {
        runtime.knockbackPredictor().recordKnockback(
                playerId, IntelRuntime.toEnginePos(posBefore), strength, timestampMs);
    }

    /** 击退校验入口：击退后若干 tick 调用。 */
    public Optional<DetectionResult> checkKnockback(String playerId, Position actualAfter, boolean wasKnockedBack) {
        return runtime.knockbackPredictor()
                .check(playerId, IntelRuntime.toEnginePos(actualAfter), wasKnockedBack).map(ev -> {
                    runtime.record(playerId, ev);
                    return toResult(ev);
                });
    }

    /** 视线命中箱校验入口（§7.3 HitboxValidator）。 */
    public Optional<DetectionResult> checkHitbox(
            String attackerId, double eyeHeight, Position eyePos, float yawDeg,
            Position victimPos, double victimWidth, double victimHeight) {
        return runtime.hitboxValidator()
                .check(attackerId, eyeHeight, IntelRuntime.toEnginePos(eyePos), yawDeg,
                        IntelRuntime.toEnginePos(victimPos), victimWidth, victimHeight)
                .map(ev -> {
                    runtime.record(attackerId, ev);
                    return toResult(ev);
                });
    }

    static DetectionResult toResult(Evidence evidence) {
        return DetectionResult.violation(
                CheckType.COMBAT,
                evidence.detectorId(),
                (int) Math.round(evidence.confidence() * 100),
                evidence.detail(),
                evidence.timestampMs());
    }
}
