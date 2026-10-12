package com.potatotv.pacc.detection.server.prediction;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

/**
 * 预测引擎门面（技术文档 §7.1 / §7.5）。
 *
 * <p>流程：豁免判断（创造 / 旁观 / 允许飞行）→ 按状态选预测器（鞘翅→水→船→普通）
 * → 1:1 物理模拟出候选位置集合（可叠加世界副本剔除穿固体的候选）→ 与实际位置比偏差。
 * 「物理上不可能的位移即违规」（GrimAC 口径），不靠阈值猜。
 *
 * <p>产出统一为 {@link Evidence}，仅告警不处置；检查纯计算、线程安全，可在检测线程异步执行。
 */
public final class PredictionEngine {

    public static final String DETECTOR_ID = "prediction-engine";

    /** 偏差基础置信：total=0 附近即 0.5，随偏差线性放大。 */
    private static final double BASE_CONFIDENCE = 0.5;

    /** total 偏差超过该格数直接满置信。 */
    private static final double FULL_CONFIDENCE_OFFSET = 1.0;

    /** 每玩家的世界副本；null 表示无世界数据（不做穿固体过滤）。 */
    private final Map<String, WorldReplica> worlds;

    public PredictionEngine(Map<String, WorldReplica> worlds) {
        this.worlds = worlds;
    }

    /** 无世界副本的便捷构造。 */
    public PredictionEngine() {
        this(null);
    }

    /**
     * 预测并比较：返回偏差结果；创造 / 旁观 / 允许飞行等豁免场景返回 empty。
     */
    public Optional<OffsetResult> evaluate(PlayerState prev, TickInput input, Position actual) {
        if (prev == null || input == null || actual == null) {
            return Optional.empty();
        }
        if (prev.isCreativeLike() || prev.flightAllowed()) {
            return Optional.empty();
        }
        MovementPredictor engine = MovementPredictor.selectEngine(prev);
        Set<PredictedPosition> predicted = engine.predict(prev, input);
        WorldReplica world = worlds == null ? null : worlds.get(prev.playerId());
        if (world != null) {
            // 世界副本参与：剔除落进固体方块内部的候选（不可能位置）；
            // 若全部候选都被剔除（预测器与世界数据不一致），保留原集合避免误报
            Set<PredictedPosition> solidFree = new HashSet<>(predicted.size());
            for (PredictedPosition candidate : predicted) {
                if (!world.hasBlockAt(candidate.x(), candidate.y(), candidate.z())) {
                    solidFree.add(candidate);
                }
            }
            if (!solidFree.isEmpty()) {
                predicted = solidFree;
            }
        }
        return Optional.of(engine.compare(predicted, actual));
    }

    /**
     * 完整检查：偏差超出容忍范围时产出证据。
     * 置信映射：total 偏差越大越可信，{@code min(1.0, 0.5 + total)}；total &gt; 1 格直接满置信。
     *
     * <p>输入未知时的物理包络：本引擎拿不到客户端逐 tick 输入（真实输入流需协议层解析），
     * 水平方向任何「合法输入可达」的位移都不能判违规。因此没有世界副本数据时，
     * 只有超出物理上限的位移（上升 &gt; 跳跃/台阶包络、下落 &gt; 终端速度、水平 &gt; 疾跑跳跃上限）
     * 才构成证据——这是「物理不可绕过」在无输入流条件下的保守形态。
     * 有世界副本数据时走严格候选匹配（世界数据已把「贴地站立」等分支钉死）。
     */
    public Optional<Evidence> check(PlayerState prev, TickInput input, Position actual) {
        return evaluate(prev, input, actual).flatMap(offset -> {
            if (offset.withinTolerance()) {
                return Optional.empty();
            }
            WorldReplica world = worlds == null ? null : worlds.get(prev.playerId());
            if (world == null && !isPhysicallyImpossible(prev, actual)) {
                // 包络内的偏差在无世界数据时都可能是合法输入造成，不判
                return Optional.empty();
            }
            double confidence = offset.total() > FULL_CONFIDENCE_OFFSET
                    ? 1.0
                    : Math.min(1.0, BASE_CONFIDENCE + offset.total());
            // 反推最佳候选坐标（actual - offset），detail 保留 3 位小数避免日志噪音
            double px = actual.x() - offset.dx();
            double py = actual.y() - offset.dy();
            double pz = actual.z() - offset.dz();
            String detail = String.format(Locale.ROOT,
                    "predicted(%.3f, %.3f, %.3f) actual(%.3f, %.3f, %.3f) offset=%.3f",
                    px, py, pz, actual.x(), actual.y(), actual.z(), offset.total());
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, confidence, detail));
        });
    }

    /** 实际位移是否超出无输入流条件下的物理上限。 */
    private static boolean isPhysicallyImpossible(PlayerState prev, Position actual) {
        double rise = actual.y() - prev.y();
        double fall = prev.y() - actual.y();
        double horizontal = Math.hypot(actual.x() - prev.x(), actual.z() - prev.z());
        double maxHorizontal = PhysicsSimulator.maxHorizontalPerTick(prev.effectLevel(PlayerState.EFFECT_SPEED));
        return rise > PhysicsSimulator.MAX_LEGIT_RISE_PER_TICK
                || fall > PhysicsSimulator.MAX_LEGIT_FALL_PER_TICK
                || horizontal > maxHorizontal;
    }
}
