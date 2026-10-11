package com.potatotv.pacc.forge.mixin;

import com.potatotv.pacc.forge.PaccClientState;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 红屏期间吞掉鼠标点击与移动。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void pacc$blockClick(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (PaccClientState.get().keyboardLock().isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void pacc$blockMove(long window, double x, double y, CallbackInfo ci) {
        if (PaccClientState.get().keyboardLock().isLocked()) {
            ci.cancel();
        }
    }
}
