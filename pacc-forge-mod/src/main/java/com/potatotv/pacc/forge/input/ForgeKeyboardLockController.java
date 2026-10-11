package com.potatotv.pacc.forge.input;

import com.potatotv.pacc.client.core.redscreen.KeyboardLockController;

/**
 * Forge 侧输入锁状态。真正的吞键由 {@code KeyboardHandlerMixin} / {@code MouseHandlerMixin} 完成，
 * 本类只维护开关，供引擎与 Mixin 查询。
 */
public final class ForgeKeyboardLockController implements KeyboardLockController {

    private volatile boolean locked;

    @Override
    public void lock() {
        locked = true;
    }

    @Override
    public void unlock() {
        locked = false;
    }

    @Override
    public boolean isLocked() {
        return locked;
    }
}
