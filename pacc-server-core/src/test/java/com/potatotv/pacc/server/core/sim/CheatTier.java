package com.potatotv.pacc.server.core.sim;

/**
 * T3 作弊档位。倍数是对齐检测阈值的**相对档位系数**（非真实客户端导出值）：
 * <ul>
 *   <li>{@link #HIDDEN}：贴着阈值的最小越界（最隐蔽）；</li>
 *   <li>{@link #MIN} / {@link #DEFAULT} / {@link #MAX}：依次加大的越界幅度。</li>
 * </ul>
 */
enum CheatTier {

    HIDDEN(1.10),
    MIN(1.25),
    DEFAULT(1.70),
    MAX(3.00);

    private final double multiplier;

    CheatTier(double multiplier) {
        this.multiplier = multiplier;
    }

    /** 相对档位系数。 */
    double multiplier() {
        return multiplier;
    }
}
