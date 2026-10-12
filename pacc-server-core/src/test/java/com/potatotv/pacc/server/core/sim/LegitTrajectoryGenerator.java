package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.detection.server.prediction.MovementPredictor;
import com.potatotv.pacc.detection.server.prediction.PlayerState;
import com.potatotv.pacc.detection.server.prediction.Position;
import com.potatotv.pacc.detection.server.prediction.PredictedPosition;
import com.potatotv.pacc.detection.server.prediction.TickInput;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * T2 合法玩家轨迹生成器。
 *
 * <p>核心口径（保证确定性、不出假阳性）：把「合法」定义为**与物理预测器一致**——
 * 用 {@code MovementPredictor} 的预测结果作为「实际位置」，再叠加**小于容差的噪声**
 * （{@link #NOISE} 远小于 {@code PhysicsSimulator.ROUNDING_TOLERANCE}=0.03）。
 * 这样 {@code PredictionEngine} 的 offset 恒在容差内，必然不告警；落到服务端时位移也仍在物理包络内。
 *
 * <p>作弊注入（T3）则在预测结果上叠加超过容差的偏差，与本类无关。
 *
 * <p>随机源为固定 seed 的 {@link Random}，不使用真实时间、网络或文件。
 */
final class LegitTrajectoryGenerator {

    /** 服务器标称 tick 时长（ms）。 */
    static final long TICK_MS = 50L;

    /** 地面高度（格）。 */
    private static final double GROUND_Y = 64.0;

    /** 单轴噪声上限，远小于 0.03 容差。 */
    static final double NOISE = 0.008;

    /** 停顿排布的随机抖动幅度（tick）：避免固定节奏被「自动化周期」启发式误判。 */
    private static final int RUN_JITTER = 12;
    private static final int PAUSE_JITTER = 4;

    private final String playerId;
    private final Random rng;
    private final int sampleCount;

    // 可变玩家状态
    private double x;
    private double y;
    private double z;
    private double vx;
    private double vy;
    private double vz;
    private final float yaw;
    private final float pitch;
    private boolean onGround;
    private double fallDistance;

    private final Profile profile;

    LegitTrajectoryGenerator(SimScenario scenario, String playerId, long seed, int sampleCount) {
        this.playerId = playerId;
        this.rng = new Random(seed);
        this.sampleCount = sampleCount;
        this.profile = Profile.of(scenario);
        this.yaw = profile.yaw();
        this.pitch = profile.pitch();
    }

    /** 生成整段合法轨迹样本。 */
    List<LegitMoveSample> generate() {
        List<LegitMoveSample> samples = new ArrayList<>(sampleCount);
        boolean[] pauseMask = buildPauseMask();
        x = profile.startX();
        y = profile.startY();
        z = profile.startZ();
        vx = 0.0;
        vy = 0.0;
        vz = 0.0;
        onGround = profile.startOnGround();
        fallDistance = 0.0;

        PlayerPosition prevServer = new PlayerPosition(playerId,
                new com.potatotv.pacc.server.core.model.Position(x, y, z),
                yaw, pitch, -TICK_MS, onGround, profile.mode());

        for (int tick = 0; tick < sampleCount; tick++) {
            long now = tick * TICK_MS;
            boolean pausing = pauseMask[tick];
            if (pausing && (tick == 0 || !pauseMask[tick - 1])) {
                // 停顿起点清空水平速度，打断「恒定速度直线」累计（BehaviorIntelCheck 的直线度启发式）
                vx = 0.0;
                vz = 0.0;
            }
            TickInput input = inputFor(tick, pausing);
            PlayerState prev = snapshot(now);

            double ax;
            double ay;
            double az;
            boolean nextOnGround;
            double nextVx;
            double nextVy;
            double nextVz;

            if (profile.exemptCreative()) {
                // 创造模式：直接构造超阈位移，用于验证豁免（本应在预测/移动检查里被跳过）。
                // 慢速段仍保持轻微位移（水平速度落在 0.03~0.05 之间）：既让行为签名恒为「移动」
                // （active==n，跳过自动化周期判定），又打断直线度累计（speed<=0.05，避免 Baritone 判定）。
                double sx = pausing ? 0.03 : 0.6;
                double sy = pausing ? 0.0 : 0.25;
                double sz = pausing ? 0.03 : 0.6;
                ax = x + sx;
                ay = y + sy;
                az = z + sz;
                nextOnGround = false;
                nextVx = sx;
                nextVy = sy;
                nextVz = sz;
            } else {
                MovementPredictor predictor = MovementPredictor.selectEngine(prev);
                Set<PredictedPosition> predicted = predictor.predict(prev, input);
                if (profile.grounded() && !prev.onGround() && lowestY(predicted) <= GROUND_Y) {
                    // 落地：把状态重建为着地状态，落点即为地面候选，保持预测偏差在容差内
                    y = GROUND_Y;
                    vy = 0.0;
                    onGround = true;
                    prev = snapshot(now);
                    predictor = MovementPredictor.selectEngine(prev);
                    predicted = predictor.predict(prev, input);
                }
                PredictedPosition candidate = selectCandidate(predicted, prev);
                ax = candidate.x() + noise();
                ay = candidate.y() + noise();
                az = candidate.z() + noise();
                if (profile.grounded() && ay < GROUND_Y) {
                    ay = GROUND_Y;
                }
                nextOnGround = candidate.onGround();
                nextVx = candidate.vx();
                nextVy = candidate.vy();
                nextVz = candidate.vz();
            }

            PlayerPosition serverTo = new PlayerPosition(playerId,
                    new com.potatotv.pacc.server.core.model.Position(ax, ay, az),
                    yaw, pitch, now, nextOnGround, profile.mode());

            samples.add(new LegitMoveSample(playerId, prev, input, new Position(ax, ay, az),
                    prevServer, serverTo));

            x = ax;
            y = ay;
            z = az;
            vx = nextVx;
            vy = nextVy;
            vz = nextVz;
            onGround = nextOnGround;
            prevServer = serverTo;
        }
        return samples;
    }

    /**
     * 生成非周期的停顿掩码：移动段长度、停顿段长度都带随机抖动，
     * 避免固定节奏被 {@code PatternCluster} 的「自动化周期」启发式误判（创造模式移动速度 > 0.05 时尤其明显）。
     */
    private boolean[] buildPauseMask() {
        boolean[] mask = new boolean[sampleCount];
        if (profile.pausePeriod() <= 0) {
            return mask;
        }
        // 创造模式移动速度 > 0.03（行为签名恒为「移动」），需更高频、更不规则的停顿，
        // 让移动/停顿签名像真人一样交错，避免被「自动化周期」启发式误判。
        int runBase = profile.pausePeriod();
        int runJit = RUN_JITTER;
        int pauseBase = profile.pauseLen();
        int pauseJit = PAUSE_JITTER;
        if (profile.exemptCreative()) {
            runBase = 8;
            runJit = 8;
            pauseBase = 6;
            pauseJit = 8;
        }
        int tick = 0;
        while (tick < sampleCount) {
            tick += runBase + rng.nextInt(runJit + 1);
            int pauseLen = pauseBase + rng.nextInt(pauseJit + 1);
            for (int i = 0; i < pauseLen && tick < sampleCount; i++) {
                mask[tick++] = true;
            }
        }
        return mask;
    }

    private TickInput inputFor(int tick, boolean pausing) {
        float forward = pausing ? 0f : 1f;
        boolean sprint = profile.sprinting() && !pausing;
        boolean jump = profile.jumping() && !pausing && onGround
                && tick % profile.jumpPeriod() == 0;
        return new TickInput(forward, 0f, jump, profile.sneaking(), sprint, tick * TICK_MS);
    }

    private PlayerState snapshot(long timestampMs) {
        return new PlayerState(playerId, x, y, z, vx, vy, vz, yaw, pitch, onGround,
                profile.inWater(), profile.inLava(), profile.gliding(), profile.ridingBoat(),
                profile.sprinting(), profile.sneaking(), false,
                false, false, false, false,
                false, profile.gameModeName(), fallDistance, Map.of(), timestampMs);
    }

    private static double lowestY(Set<PredictedPosition> predicted) {
        double lowest = Double.MAX_VALUE;
        for (PredictedPosition candidate : predicted) {
            lowest = Math.min(lowest, candidate.y());
        }
        return lowest;
    }

    /**
     * 选择落点候选：着地时取贴地候选（y 最接近当前 y，等价于碰撞抵消重力），
     * 空中取中性候选（3 个 ±0.03 候选的中间那个），保证实际位置与预测一致。
     */
    private static PredictedPosition selectCandidate(Set<PredictedPosition> predicted, PlayerState prev) {
        List<PredictedPosition> list = new ArrayList<>(predicted);
        list.sort(Comparator.comparingDouble(PredictedPosition::y)
                .thenComparingDouble(PredictedPosition::x)
                .thenComparingDouble(PredictedPosition::z));
        if (prev.onGround()) {
            PredictedPosition best = list.get(0);
            double bestDiff = Math.abs(best.y() - prev.y());
            for (PredictedPosition candidate : list) {
                double diff = Math.abs(candidate.y() - prev.y());
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = candidate;
                }
            }
            return best;
        }
        return list.get(list.size() / 2);
    }

    private double noise() {
        return (rng.nextDouble() * 2.0 - 1.0) * NOISE;
    }

    /** 场景物理参数画像。 */
    private record Profile(
            GameMode mode,
            double startX, double startY, double startZ,
            boolean startOnGround,
            boolean grounded, boolean inWater, boolean inLava, boolean gliding, boolean ridingBoat,
            boolean sprinting, boolean sneaking,
            boolean jumping, int jumpPeriod,
            float yaw, float pitch,
            int pausePeriod, int pauseLen,
            boolean exemptCreative) {

        static Profile of(SimScenario scenario) {
            return switch (scenario) {
                // 走与跑在本预测器里共用同一移动属性（NormalPredictor 不区分 walk/run），
                // 仅停顿占空比不同以示区分；游泳/乘船/滑翔各有独立预测器。
                case WALK -> survival(64, true, false, false, false, false, 40, 10);
                case RUN -> survival(64, true, false, false, false, false, 90, 8);
                case SPRINT -> new Profile(GameMode.SURVIVAL, 0, 64, 0, true,
                        true, false, false, false, false, true, false, false, 100, 0f, 0f, 40, 10, false);
                case JUMP -> new Profile(GameMode.SURVIVAL, 0, 64, 0, true,
                        true, false, false, false, false, false, false, true, 25, 0f, 0f, 60, 10, false);
                case SNEAK -> new Profile(GameMode.SURVIVAL, 0, 64, 0, true,
                        true, false, false, false, false, false, true, false, 100, 0f, 0f, 40, 10, false);
                case SWIM -> new Profile(GameMode.SURVIVAL, 0, 64, 0, false,
                        false, true, false, false, false, false, false, false, 100, 0f, 0f, 40, 10, false);
                case ELYTRA -> new Profile(GameMode.SURVIVAL, 0, 200, 0, false,
                        false, false, false, true, false, false, false, false, 100, 0f, -75f, 40, 10, false);
                case BOAT -> new Profile(GameMode.SURVIVAL, 0, 64, 0, false,
                        false, true, false, false, true, false, false, false, 100, 0f, 0f, 40, 10, false);
                case BRIDGE -> new Profile(GameMode.SURVIVAL, 0, 64, 0, true,
                        true, false, false, false, false, false, true, false, 100, 0f, 0f, 40, 10, false);
                case HIGH_LATENCY -> survival(64, true, false, false, false, false, 60, 10);
                case CREATIVE -> new Profile(GameMode.CREATIVE, 0, 100, 0, false,
                        false, false, false, false, false, false, false, false, 100, 0f, 0f, 40, 10, true);
            };
        }

        private static Profile survival(double startY, boolean grounded, boolean inWater, boolean inLava,
                                        boolean gliding, boolean ridingBoat, int pausePeriod, int pauseLen) {
            return new Profile(GameMode.SURVIVAL, 0, startY, 0, grounded,
                    grounded, inWater, inLava, gliding, ridingBoat, false, false, false, 100, 0f, 0f,
                    pausePeriod, pauseLen, false);
        }

        String gameModeName() {
            return switch (mode) {
                case CREATIVE -> "creative";
                case SPECTATOR -> "spectator";
                case ADVENTURE -> "adventure";
                default -> "survival";
            };
        }
    }
}
