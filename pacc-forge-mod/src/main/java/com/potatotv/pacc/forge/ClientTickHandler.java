package com.potatotv.pacc.forge;

import com.potatotv.pacc.client.core.detection.ClientDetectionEngine;
import com.potatotv.pacc.client.core.model.CombatSample;
import com.potatotv.pacc.client.core.model.GameMode;
import com.potatotv.pacc.client.core.model.PlayerMovement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge 客户端事件适配：把游戏事件喂给与加载器无关的客户端检测引擎。
 *
 * <p>监听 {@code ClientTickEvent}（移动采样）、{@code InputEvent.InteractionKeyMappingTriggered}（攻击采样）
 * 与 {@code RenderGuiEvent.Post}（红屏绘制）。
 */
public final class ClientTickHandler {

    private final PaccClientState state;
    private PlayerMovement lastMovement;
    private boolean modsScanned;

    public ClientTickHandler(PaccClientState state) {
        this.state = state;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            lastMovement = null;
            modsScanned = false;
            return;
        }
        ClientDetectionEngine engine = state.engineFor(player.getGameProfile().getName());
        if (!modsScanned) {
            modsScanned = true;
            scanLoadedMods();
        }
        PlayerMovement current = sample(player);
        if (lastMovement != null) {
            engine.onMove(lastMovement, current);
        }
        lastMovement = current;
        state.tickRedScreenExpiry();
    }

    @SubscribeEvent
    public void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        CombatSample sample = combatSample(player);
        if (sample != null) {
            state.engineFor(player.getGameProfile().getName()).onAttack(sample);
        }
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        // 红屏画在所有 HUD 之上，避免被其它覆盖层盖住。
        state.redScreen().renderOverlay(event.getGuiGraphics());
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        lastMovement = null;
        modsScanned = false;
    }

    /** 模组加载完成后比对已装 Mod 列表。 */
    public void scanLoadedMods() {
        List<String> mods = new ArrayList<>();
        ModList.get().getMods().forEach(info -> {
            mods.add(info.getModId());
            mods.add(info.getDisplayName());
        });
        state.engineFor(currentPlayerName()).scanMods(mods);
    }

    private String currentPlayerName() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? "" : player.getGameProfile().getName();
    }

    private static PlayerMovement sample(LocalPlayer player) {
        Vec3 pos = player.position();
        return new PlayerMovement(pos.x, pos.y, pos.z, player.onGround(),
                mapGameMode(player.gameMode()), System.currentTimeMillis());
    }

    private static CombatSample combatSample(LocalPlayer player) {
        Entity target = Minecraft.getInstance().crosshairPickEntity;
        if (target == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 targetPos = target.position();
        double viewAngle = viewAngleDegrees(player, targetPos.subtract(eye));
        return new CombatSample(target.getStringUUID(), eye.x, eye.y, eye.z,
                targetPos.x, targetPos.y, targetPos.z, viewAngle, System.currentTimeMillis());
    }

    private static double viewAngleDegrees(LocalPlayer player, Vec3 toTarget) {
        Vec3 direction = toTarget.normalize();
        Vec3 look = player.getLookAngle().normalize();
        double dot = Math.max(-1.0, Math.min(1.0, direction.dot(look)));
        return Math.toDegrees(Math.acos(dot));
    }

    private static GameMode mapGameMode(GameType type) {
        return switch (type) {
            case CREATIVE -> GameMode.CREATIVE;
            case SPECTATOR -> GameMode.SPECTATOR;
            case ADVENTURE -> GameMode.ADVENTURE;
            default -> GameMode.SURVIVAL;
        };
    }
}
