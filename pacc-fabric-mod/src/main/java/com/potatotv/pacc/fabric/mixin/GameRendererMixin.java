package com.potatotv.pacc.fabric.mixin;

import com.potatotv.pacc.fabric.PaccClientState;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 红屏期间把世界清屏色染红，配合 HUD 渲染回调的全屏遮罩形成强制红屏观感。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void pacc$tintRedScreen(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (PaccClientState.get().redScreen().isActive()) {
            RenderSystem.clearColor(1.0F, 0.0F, 0.0F, 1.0F);
        }
    }
}
