package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.server.prediction.PlayerState;
import com.potatotv.pacc.detection.server.prediction.SetbackController;
import com.potatotv.pacc.detection.server.prediction.TickInput;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理预测 intel 检查（技术文档 §7.1 / §7.2：预测式移动检测 + 世界副本 + 延迟补偿）。
 *
 * <p>服务端没有客户端输入流，两层判定互补：
 * <ul>
 *   <li>物理包络（PredictionEngine）：超物理上限的位移（垂直飞升 / 超终端坠落 / 超疾跑跳跃水平速度）</li>
 *   <li>滞空上升连击（本类）：连续 &gt; {@link #MAX_RISING_AIRBORNE_TICKS} tick 滞空且持续上升/悬停——
 *       真实跳跃上升段约 6 tick，黏液高弹等极端场景可能到 10+，默认阈值取 8 并豁免创造模式；
 *       这是 Flight 在「无输入流」条件下的行为面兜底（§5.2 Flight 行）</li>
 * </ul>
 * 违规时通过 {@link SetbackController} 记录回撤点，供平台适配层执行。
 */
public final class PredictionIntelCheck implements ServerCheck {

    /** 滞空且持续上升的连击阈值（真实跳跃上升段约 6 tick）。 */
    static final int MAX_RISING_AIRBORNE_TICKS = 8;

    private final IntelRuntime runtime;
    private final ConcurrentHashMap<String, Integer> risingStreaks = new ConcurrentHashMap<>();

    public PredictionIntelCheck(IntelRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public CheckType type() {
        return CheckType.MOVE;
    }

    @Override
    public List<DetectionResult> onMove(PlayerPosition from, PlayerPosition to) {
        Position velocity = runtime.nextVelocity(to.playerId(), from.pos());
        List<DetectionResult> results = new ArrayList<>();

        updateRisingStreak(from, to);

        if (velocity != null) {
            PlayerState state = new PlayerState(
                    to.playerId(),
                    from.pos().x(), from.pos().y(), from.pos().z(),
                    velocity.x(), velocity.y(), velocity.z(),
                    from.yaw(), from.pitch(),
                    from.onGround(),
                    false, false, false, false, false, false, false,
                    false, false, false, false,
                    false,
                    gameModeName(from.gameMode()),
                    0.0,
                    Map.of(),
                    from.timestampMs());

            Optional<Evidence> evidence = runtime.predictionEngine().check(
                    state, TickInput.idle(from.timestampMs()), IntelRuntime.toEnginePos(to.pos()));
            evidence.ifPresent(ev -> {
                results.add(toResult(ev));
                runtime.record(to.playerId(), ev);
                // 回撤到上一帧位置（§4.2 SetbackController）：由平台适配层取用执行
                runtime.setbackController().setback(to.playerId(), IntelRuntime.toEnginePos(from.pos()));
            });
        }

        int streak = risingStreaks.getOrDefault(to.playerId(), 0);
        if (streak > MAX_RISING_AIRBORNE_TICKS && results.isEmpty()) {
            results.add(DetectionResult.violation(
                    CheckType.MOVE, "prediction-flight", 85,
                    "连续 " + streak + " tick 滞空上升/悬停（物理跳跃上限约 6 tick）", to.timestampMs()));
        }
        runtime.fuse(to.playerId(), type(), runtime.latencyCompensator().estimatedLatencyMs(),
                        from.gameMode() == GameMode.CREATIVE || from.gameMode() == GameMode.SPECTATOR)
                .ifPresent(results::add);
        return results;
    }

    /** 滞空上升连击：着地清零；创造/旁观不累计（§5.2 误报抑制列）。 */
    private void updateRisingStreak(PlayerPosition from, PlayerPosition to) {
        String playerId = to.playerId();
        if (from.gameMode() == GameMode.CREATIVE || from.gameMode() == GameMode.SPECTATOR
                || to.gameMode() == GameMode.CREATIVE || to.gameMode() == GameMode.SPECTATOR) {
            risingStreaks.remove(playerId);
            return;
        }
        if (to.onGround()) {
            risingStreaks.remove(playerId);
            return;
        }
        double rise = to.pos().y() - from.pos().y();
        if (rise > 0.01) {
            risingStreaks.merge(playerId, 1, Integer::sum);
        } else {
            risingStreaks.remove(playerId);
        }
    }

    @Override
    public void reset(String playerId) {
        runtime.clear(playerId);
        risingStreaks.remove(playerId);
    }

    private static String gameModeName(GameMode mode) {
        if (mode == null) {
            return "survival";
        }
        return switch (mode) {
            case CREATIVE -> "creative";
            case SPECTATOR -> "spectator";
            case ADVENTURE -> "adventure";
            default -> "survival";
        };
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
