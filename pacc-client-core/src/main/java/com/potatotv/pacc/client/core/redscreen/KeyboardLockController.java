package com.potatotv.pacc.client.core.redscreen;

/**
 * 键盘 / 鼠标输入锁定的加载器无关抽象。
 *
 * <p>红屏期间吞掉本地键盘与鼠标输入。真正拦截按键的是加载器层的输入钩子
 * （Forge 的 {@code InputEvent.Key} / Fabric 的 {@code Keyboard} Mixin），本接口只暴露开关状态。
 */
public interface KeyboardLockController {

    /** 锁定输入。重复调用应为幂等。 */
    void lock();

    /** 解锁输入。 */
    void unlock();

    /** 当前是否处于锁定状态。 */
    boolean isLocked();
}
