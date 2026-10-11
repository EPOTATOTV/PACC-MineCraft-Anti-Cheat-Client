package com.potatotv.pacc.fabric.mixin;

import com.potatotv.pacc.fabric.PaccClientState;

import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 红屏期间吞掉键盘输入。只在锁定状态取消按键处理。
 */
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void pacc$blockKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (PaccClientState.get().keyboardLock().isLocked()) {
            ci.cancel();
        }
    }
}
