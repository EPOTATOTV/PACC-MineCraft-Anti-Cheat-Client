package com.potatotv.pacc.forge.mixin;

import com.potatotv.pacc.forge.PaccClientState;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 红屏期间吞掉键盘输入。只在锁定状态取消按键处理，不改变其它行为。
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void pacc$blockKeyPress(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (PaccClientState.get().keyboardLock().isLocked()) {
            ci.cancel();
        }
    }
}
