package com.potatotv.pacc.server.core.check;

/**
 * 需要感知服务端刻的检查项。平台适配层每刻调用一次 {@code DetectionEngine#handleTick}，
 * 引擎按类型分发给所有实现了本接口的检查项。
 *
 * <p>存在的理由：包时序（Timer/Blink）与性能自适应都要求知道真实的刻长，而真实刻长只有平台
 * 侧量得出来。写死 50ms 会在卡服时把所有玩家判成 Timer（见
 * {@code PerformanceCalibrator} 的类注释）。
 */
public interface TickAware {

    /** 一个服务端刻的边界；{@code nowMs} 为平台侧时钟（epoch 毫秒）。 */
    void onTick(long nowMs);
}
