package com.potatotv.pacc.server.core.sim;

/**
 * T3 作弊来源（客户端/外挂内核）与档位参数。
 *
 * <p>来源仅用于「相对档位」表达——{@link #factor()} 是该来源相对基准的缩放系数，
 * 用代码内置的相对档位系数表达不同来源的强弱差异，**不是任何真实客户端的导出数值**。
 * 同理 {@link Tier} 的倍数是对齐检测阈值的相对档位，不等同真实客户端参数。
 */
enum CheatSource {

    WURST(1.00),
    LIQUID_BOUNCE(0.98),
    METEOR(1.05),
    THUNDER_HACK(1.02),
    BLEACH_HACK(0.99),
    LAMBDA(1.01);

    private final double factor;

    CheatSource(double factor) {
        this.factor = factor;
    }

    /** 相对档位系数（相对基准，非真实导出值）。 */
    double factor() {
        return factor;
    }
}
