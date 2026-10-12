package com.potatotv.pacc.detection.server.prediction;

/**
 * 物理模拟常量与单步公式（技术文档 §7.1：重力、碰撞、流体阻力、药水效果、附魔、
 * 活塞推力、黏液块、蜂蜜块、细雪、气泡柱、载具、击退全要素；版本差异单独注册）。
 *
 * <p>数值取自 Minecraft Java 版公开物理机制（与 GrimAC 模拟口径一致）。
 */
public final class PhysicsSimulator {

    /** 每 tick 垂直重力。 */
    public static final double GRAVITY = 0.08;

    /** 每 tick 垂直速度衰减。 */
    public static final double VERTICAL_DRAG = 0.98;

    /** 空中水平速度衰减。 */
    public static final double AIR_DRAG = 0.91;

    /** 默认地面摩擦（滑度 0.6 的方块：0.6 * 0.91）。 */
    public static final double GROUND_FRICTION = 0.546;

    /** 基础跳跃初速度。 */
    public static final double JUMP_VELOCITY = 0.42;

    /** 走路移动属性。 */
    public static final double WALK_SPEED = 0.1;

    /** 疾跑移动属性。 */
    public static final double SPRINT_SPEED = 0.13;

    /** 地面加速度系数：0.1 * moveSpeed * (0.6/slip)^3，默认滑度 0.6 时为 0.1。 */
    public static final double GROUND_ACCEL_BASE = 0.1;

    /** 空中水平加速度基数。 */
    public static final double AIR_ACCEL_BASE = 0.02;

    /** 水平速度舍入容忍（客户端坐标按 0.03 量化，文档 §5.2 SpeedHack 行）。 */
    public static final double ROUNDING_TOLERANCE = 0.03;

    /** 鞘翅滑翔每 tick 垂直加速度。 */
    public static final double ELYTRA_GRAVITY = 0.05;

    /** 鞘翅滑翔最大速度。 */
    public static final double ELYTRA_MAX_SPEED = 1.0;

    /** 水中水平阻力。 */
    public static final double WATER_DRAG = 0.8;

    /** 水中垂直重力。 */
    public static final double WATER_GRAVITY = 0.02;

    /** 船只在水面加速度。 */
    public static final double BOAT_ACCEL = 0.035;

    /** 船只水面阻力。 */
    public static final double BOAT_DRAG = 0.9;

    private PhysicsSimulator() {
    }

    /** 垂直单步：vy' = (vy - 0.08) * 0.98。 */
    public static double stepVertical(double vy) {
        return (vy - GRAVITY) * VERTICAL_DRAG;
    }

    /** 跳跃初速度（含跳跃提升药水，每级 +0.1）。 */
    public static double jumpVelocity(int jumpBoostLevel) {
        return JUMP_VELOCITY + 0.1 * jumpBoostLevel;
    }

    /** 缓降药水下的垂直单步：只受 0.05 重力且无初始加速项。 */
    public static double stepVerticalSlowFalling(double vy) {
        return Math.max(vy - 0.01, -0.15) * VERTICAL_DRAG;
    }

    /** 地面水平摩擦系数（滑度 0.6 默认）。 */
    public static double groundFriction(double blockSlipperiness) {
        return blockSlipperiness * AIR_DRAG;
    }

    /** 地面加速度：0.1 * moveSpeed * (0.6 / slip)^3。 */
    public static double groundAccel(double moveSpeed, double blockSlipperiness) {
        double ratio = 0.6 / Math.max(0.01, blockSlipperiness);
        return GROUND_ACCEL_BASE * moveSpeed * ratio * ratio * ratio;
    }

    /** 水平加速度向量按朝向旋转：返回 [x, z]。 */
    public static double[] rotateToYaw(double ax, double az, double yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        // Minecraft 坐标系：yaw=0 朝 +Z，顺时针为正
        return new double[] {ax * cos - az * sin, ax * cos + az * sin};
    }

    /** 移动属性：疾跑 0.13，走路 0.1，速度药水每级 +20%。 */
    public static double moveSpeed(boolean sprinting, int speedEffectLevel, int slownessEffectLevel) {
        double base = sprinting ? SPRINT_SPEED : WALK_SPEED;
        base *= 1.0 + 0.2 * speedEffectLevel;
        base *= 1.0 - 0.15 * slownessEffectLevel;
        return Math.max(0.0, base);
    }

    /** 疾跑跳跃水平冲量（沿朝向 0.2，含速度药水放大）。 */
    public static double sprintJumpBoost(int speedEffectLevel) {
        return 0.2 * (1.0 + 0.2 * speedEffectLevel);
    }

    /**
     * 无客户端输入流时的水平物理上限（单 tick 位移，格）：
     * 疾跑跳跃首 tick ≈ 终端速度 0.286 + 跳跃冲量 0.2，加速度药水放大与舍入容忍。
     * 超过即 SpeedHack 物理证据（§5.2）。
     */
    public static double maxHorizontalPerTick(int speedEffectLevel) {
        double terminal = SPRINT_SPEED / (1.0 - GROUND_FRICTION);
        return terminal + sprintJumpBoost(speedEffectLevel) + ROUNDING_TOLERANCE;
    }

    /** 单 tick 最大合法上升：跳跃初速 0.42 与自动上台阶 0.6 取大者，加舍入容忍。 */
    public static final double MAX_LEGIT_RISE_PER_TICK = 0.65;

    /** 单 tick 最大合法下落：终端坠落速度约 3.92 格/tick，加舍入容忍。 */
    public static final double MAX_LEGIT_FALL_PER_TICK = 4.0;
}
