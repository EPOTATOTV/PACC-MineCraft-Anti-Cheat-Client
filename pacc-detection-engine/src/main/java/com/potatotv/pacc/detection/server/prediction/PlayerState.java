package com.potatotv.pacc.detection.server.prediction;

import java.util.List;
import java.util.Map;

/**
 * 玩家状态快照：预测引擎的输入（技术文档 §7.1 PlayerState）。
 *
 * <p>物理要素全覆盖口径（文档 §7.1）：重力、碰撞、流体阻力、药水效果、附魔、
 * 活塞推力、黏液块、蜂蜜块、细雪、气泡柱、载具、击退，全部通过本状态与
 * {@link TickInput} 表达，各预测器按状态选择物理分支。
 */
public record PlayerState(
        String playerId,
        double x,
        double y,
        double z,
        double vx,
        double vy,
        double vz,
        float yaw,
        float pitch,
        boolean onGround,
        boolean inWater,
        boolean inLava,
        boolean isGliding,
        boolean ridingBoat,
        boolean sprinting,
        boolean sneaking,
        boolean climbing,
        boolean onSlimeBlock,
        boolean onHoneyBlock,
        boolean inPowderSnow,
        boolean inBubbleColumn,
        boolean flightAllowed,
        String gameMode,
        double fallDistance,
        Map<String, Integer> effects,
        long timestampMs,
        double blockSlipperiness,
        double blockBounciness) {

    /**
     * 兼容构造：未提供脚下方块物理时，按默认地面滑度 0.6、无反弹处理。
     */
    public PlayerState(String playerId, double x, double y, double z, double vx, double vy, double vz,
                       float yaw, float pitch, boolean onGround, boolean inWater, boolean inLava,
                       boolean isGliding, boolean ridingBoat, boolean sprinting, boolean sneaking,
                       boolean climbing, boolean onSlimeBlock, boolean onHoneyBlock, boolean inPowderSnow,
                       boolean inBubbleColumn, boolean flightAllowed, String gameMode, double fallDistance,
                       Map<String, Integer> effects, long timestampMs) {
        this(playerId, x, y, z, vx, vy, vz, yaw, pitch, onGround, inWater, inLava, isGliding, ridingBoat,
                sprinting, sneaking, climbing, onSlimeBlock, onHoneyBlock, inPowderSnow, inBubbleColumn,
                flightAllowed, gameMode, fallDistance, effects, timestampMs, 0.6, 0.0);
    }

    public PlayerState {
        if (blockSlipperiness <= 0.0 || blockSlipperiness > 1.0) {
            blockSlipperiness = 0.6;
        }
        if (blockBounciness < 0.0 || blockBounciness > 1.0) {
            blockBounciness = 0.0;
        }
    }

    /** 药水效果键。 */
    public static final String EFFECT_SPEED = "speed";
    public static final String EFFECT_SLOWNESS = "slowness";
    public static final String EFFECT_LEVITATION = "levitation";
    public static final String EFFECT_JUMP_BOOST = "jump_boost";
    public static final String EFFECT_SLOW_FALLING = "slow_falling";
    public static final String EFFECT_DOLPHINS_GRACE = "dolphins_grace";

    /** 药水效果等级（无效果为 0）。 */
    public int effectLevel(String key) {
        return effects == null ? 0 : effects.getOrDefault(key, 0);
    }

    /** 创造 / 旁观模式（物理预测豁免）。 */
    public boolean isCreativeLike() {
        return "creative".equalsIgnoreCase(gameMode) || "spectator".equalsIgnoreCase(gameMode);
    }
}
