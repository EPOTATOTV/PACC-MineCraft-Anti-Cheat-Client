package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.PlayerPosition;

/**
 * 需要观察移动流但不属于 MOVE 检查项的检查（例如 X-Ray 的探索路径统计）。
 *
 * <p>引擎按 {@link ServerCheck#type()} 分发检测结果，一个检查项只能属于一种类型；
 * 但「挖矿统计」属于 INTERACTION，「探索距离」却来自移动事件。用一个窄接口把这些跨类型的
 * 只读观察口显式列出来，比给检查项塞多重类型更清楚。
 */
public interface MoveAware {

    /** 观察一次移动（已过滤掉无位移的纯视角旋转）。 */
    void onMoveObserved(PlayerPosition from, PlayerPosition to);
}
