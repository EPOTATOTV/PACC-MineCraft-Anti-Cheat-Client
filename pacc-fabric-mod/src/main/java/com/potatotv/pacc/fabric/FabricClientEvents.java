package com.potatotv.pacc.fabric;

import com.potatotv.pacc.client.core.detection.ClientDetectionEngine;
import com.potatotv.pacc.client.core.model.CombatSample;
import com.potatotv.pacc.client.core.model.GameMode;
import com.potatotv.pacc.client.core.model.PlayerMovement;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric 客户端事件适配：把 Fabric Event API 的事件喂给与加载器无关的客户端检测引擎。
 *
 * <p>用 Fabric API 的事件（tick / HUD 渲染 / 攻击实体），移动采样由
 * {@code ClientPlayerEntityMixin} 在本地玩家 tick 时回调 {@link #sampleMovement}。
 */
public final class FabricClientEvents {

    private static volatile PaccClientState state;
    private static PlayerMovement lastMovement;
    private static boolean modsScanned;

    private FabricClientEvents() {
    }

    public static void register(PaccClientState clientState) {
        state = clientState;
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            PaccClientState current = state;
            if (current != null) {
                current.redScreen().renderOverlay(context);
            }
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            onAttack(entity);
            return ActionResult.PASS;
        });
    }

    private static void onTick() {
        PaccClientState current = state;
        if (current == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            lastMovement = null;
            modsScanned = false;
            return;
        }
        if (!modsScanned) {
            modsScanned = true;
            scanLoadedMods(current, player.getGameProfile().getName());
        }
        current.tickRedScreenExpiry();
    }

    /** 由 {@code ClientPlayerEntityMixin.tick} 在本地玩家 tick 之后调用。 */
    public static void sampleMovement(ClientPlayerEntity player) {
        PaccClientState current = state;
        if (current == null || player == null) {
            return;
        }
        ClientDetectionEngine engine = current.engineFor(player.getGameProfile().getName());
        PlayerMovement sample = sample(player);
        if (lastMovement != null) {
            engine.onMove(lastMovement, sample);
        }
        lastMovement = sample;
    }

    private static void onAttack(Entity target) {
        PaccClientState current = state;
        if (current == null || target == null) {
            return;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3d eye = player.getEyePos();
        Vec3d targetPos = target.getPos();
        double viewAngle = viewAngleDegrees(player, targetPos.subtract(eye));
        CombatSample sample = new CombatSample(target.getUuidAsString(), eye.x, eye.y, eye.z,
                targetPos.x, targetPos.y, targetPos.z, viewAngle, System.currentTimeMillis());
        current.engineFor(player.getGameProfile().getName()).onAttack(sample);
    }

    private static void scanLoadedMods(PaccClientState current, String playerName) {
        List<String> mods = new ArrayList<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            mods.add(container.getMetadata().getId());
            mods.add(container.getMetadata().getName());
        }
        current.engineFor(playerName).scanMods(mods);
    }

    private static PlayerMovement sample(ClientPlayerEntity player) {
        Vec3d pos = player.getPos();
        MinecraftClient client = MinecraftClient.getInstance();
        net.minecraft.world.GameMode gameMode = client.interactionManager == null
                ? net.minecraft.world.GameMode.SURVIVAL
                : client.interactionManager.getCurrentGameMode();
        return new PlayerMovement(pos.x, pos.y, pos.z, player.isOnGround(),
                mapGameMode(gameMode), System.currentTimeMillis());
    }

    private static double viewAngleDegrees(ClientPlayerEntity player, Vec3d toTarget) {
        Vec3d direction = toTarget.normalize();
        Vec3d look = player.getRotationVec(1.0F).normalize();
        double dot = Math.max(-1.0, Math.min(1.0, direction.dotProduct(look)));
        return Math.toDegrees(Math.acos(dot));
    }

    private static GameMode mapGameMode(net.minecraft.world.GameMode gameMode) {
        return switch (gameMode) {
            case CREATIVE -> GameMode.CREATIVE;
            case SPECTATOR -> GameMode.SPECTATOR;
            case ADVENTURE -> GameMode.ADVENTURE;
            default -> GameMode.SURVIVAL;
        };
    }
}
