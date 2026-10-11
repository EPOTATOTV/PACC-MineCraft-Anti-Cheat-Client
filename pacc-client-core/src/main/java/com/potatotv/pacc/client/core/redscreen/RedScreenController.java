package com.potatotv.pacc.client.core.redscreen;

/**
 * 红屏强制警告的加载器无关抽象。
 *
 * <p>内核只负责在检测命中时调用 {@link #show}，具体怎么画（全屏红屏、文案、倒计时）由加载器层实现
 * （Forge / Fabric 各自的渲染钩子）。玩家端只有警告与留痕，没有封禁 / 踢出。
 */
public interface RedScreenController {

    /** 显示红屏警告；已显示时应更新为最新警告而不是叠加。 */
    void show(RedScreenAlert alert);

    /** 隐藏红屏警告。 */
    void hide();

    /** 当前是否处于红屏状态。 */
    boolean isActive();
}
