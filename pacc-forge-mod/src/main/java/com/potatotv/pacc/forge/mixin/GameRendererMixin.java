package com.potatotv.pacc.forge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.potatotv.pacc.forge.PaccClientState;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 红屏期间把世界清屏色染红，配合 {@code RenderGuiEvent.Post} 的全屏遮罩形成强制红屏观感。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void pacc$tintRedScreen(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        if (PaccClientState.get().redScreen().isActive()) {
            RenderSystem.clearColor(1.0F, 0.0F, 0.0F, 1.0F);
        }
    }
}
