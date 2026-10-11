package com.potatotv.pacc.fabric.mixin;

import com.potatotv.pacc.fabric.PaccClientState;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 红屏期间吞掉鼠标点击与移动。
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void pacc$blockButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (PaccClientState.get().keyboardLock().isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void pacc$blockCursor(long window, double x, double y, CallbackInfo ci) {
        if (PaccClientState.get().keyboardLock().isLocked()) {
            ci.cancel();
        }
    }
}
