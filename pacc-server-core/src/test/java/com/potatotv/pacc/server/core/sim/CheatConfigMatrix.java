package com.potatotv.pacc.server.core.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * T3 作弊配置矩阵：来源 × 四档 × 各模块，生成注入规格列表。
 *
 * <p>参数表全部内置为「相对档位系数」（见 {@link CheatSource} / {@link CheatTier} 注释），
 * 用阈值 × 系数的形式表达越界幅度，**不代表任何真实客户端的精确数值**。
 * 各模块的越界幅度都保证超过对应检测阈值，用于验证「已实现项不可被绕过」。
 */
final class CheatConfigMatrix {

    // 各检测项的阈值锚点（与引擎配置一致）
    private static final double SPEED_ANCHOR = 0.63;      // MoveCheck: 0.42 * 1.5
    private static final double FLY_ANCHOR = 0.42;        // maxVerticalClimbPerTick
    private static final double REACH_ANCHOR = 3.1;       // maxReachDistance
    private static final double CPS_ANCHOR = 16.0;        // maxCps
    private static final double SWITCH_ANCHOR = 5.0;      // 目标切换 > 4
    private static final double TIMER_RATE_ANCHOR = 1.005; // PacketTiming.TIMER_THRESHOLD
    private static final double TIMER_TICK_MS = 50.0;     // 服务器标称 tick
    private static final double BLINK_ANCHOR = 2000.0;    // PacketTiming BLINK_MIN_GAP_MS
    private static final double NO_FALL_ANCHOR = 3.0;     // PacketCheck NO_FALL_MIN_DROP
    private static final double SCAFFOLD_ANCHOR = 3.0;    // SCAFFOLD_STREAK_LIMIT
    private static final double FAST_EAT_ANCHOR = 800.0;  // FAST_EAT_MIN_INTERVAL_MS
    private static final double AUTO_TOTEM_ANCHOR = 500.0; // AUTO_TOTEM_MIN_INTERVAL_MS

    private CheatConfigMatrix() {
    }

    /** 生成全部注入规格（来源 × 四档 × 各模块）。 */
    static List<CheatSpec> build() {
        List<CheatSpec> specs = new ArrayList<>();
        for (CheatSource source : CheatSource.values()) {
            for (CheatTier tier : CheatTier.values()) {
                for (CheatModule module : CheatModule.values()) {
                    specs.add(new CheatSpec(source, tier, module, magnitude(module, tier, source)));
                }
            }
        }
        return specs;
    }

    /** 按「阈值锚点 × 相对档位系数」计算越界幅度，并夹到保证越界的区间内。 */
    static double magnitude(CheatModule module, CheatTier tier, CheatSource source) {
        double f = tier.multiplier() * source.factor();
        return switch (module) {
            case SPEED -> Math.max(SPEED_ANCHOR * 1.02, SPEED_ANCHOR * f);
            case FLY -> Math.max(FLY_ANCHOR * 1.02, FLY_ANCHOR * f);
            case REACH -> clamp(REACH_ANCHOR * f, REACH_ANCHOR * 1.02, 5.5);
            case AUTO_CLICKER -> Math.max(17.0, Math.ceil(CPS_ANCHOR * f));
            case KILL_AURA -> Math.max(6.0, Math.ceil(SWITCH_ANCHOR * f));
            case TIMER -> Math.min(49.7, TIMER_TICK_MS / (TIMER_RATE_ANCHOR * f));
            case BLINK -> Math.max(2200.0, BLINK_ANCHOR * f);
            case NO_FALL -> Math.max(3.2, NO_FALL_ANCHOR * f);
            case SCAFFOLD -> Math.max(3.0, Math.ceil(SCAFFOLD_ANCHOR * f));
            case FAST_EAT -> Math.min(780.0, FAST_EAT_ANCHOR / f);
            case AUTO_TOTEM -> Math.min(480.0, AUTO_TOTEM_ANCHOR / f);
            // phase / jesus：当前代码无对应检测项，注入正常速度的违规行为，仅用于登记「未实现」
            case PHASE -> 0.05;
            case JESUS -> 0.02;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
