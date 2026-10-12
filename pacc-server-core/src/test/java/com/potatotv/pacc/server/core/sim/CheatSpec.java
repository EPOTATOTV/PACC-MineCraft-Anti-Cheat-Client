package com.potatotv.pacc.server.core.sim;

/**
 * 一条作弊注入规格：来源 × 档位 × 模块 × 幅度。
 *
 * <p>{@code magnitude} 的物理含义按模块解释（每 tick 位移 / 攻击距离 / 发包间隔 / 下落落差 / 连续堆叠次数等），
 * 由 {@link CheatConfigMatrix} 按「检测阈值 × 相对档位系数」算出，保证越界。
 */
record CheatSpec(CheatSource source, CheatTier tier, CheatModule module, double magnitude) {

    String playerId() {
        return "cheat-" + module + "-" + source + "-" + tier;
    }
}
