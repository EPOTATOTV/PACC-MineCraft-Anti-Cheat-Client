package com.potatotv.pacc.fabric.mixin;

import com.potatotv.pacc.fabric.FabricClientEvents;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在本地玩家每 tick 之后取一份移动采样，交给客户端检测引擎做移动检测。
 *
 * <p>这是本地设备的观测，不接入游戏服务器 API。
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void pacc$sampleMovement(CallbackInfo ci) {
        FabricClientEvents.sampleMovement((ClientPlayerEntity) (Object) this);
    }
}
