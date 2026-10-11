package com.potatotv.pacc.forge.render;

import com.potatotv.pacc.client.core.redscreen.RedScreenAlert;
import com.potatotv.pacc.client.core.redscreen.RedScreenController;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Forge 侧红屏渲染。检测命中后由引擎调用 {@link #show}，{@code RenderGuiEvent.Post} 回调负责画全屏红屏。
 */
public final class ForgeRedScreenController implements RedScreenController {

    private static final int OVERLAY_ARGB = 0xE6FF0000;
    private static final Component TITLE = Component.translatable("pacc.redscreen.title");

    private volatile RedScreenAlert alert;
    private volatile long expiresAtNanos;

    @Override
    public void show(RedScreenAlert alert) {
        this.alert = alert;
        long durationMs = Math.max(0L, alert.durationMs());
        this.expiresAtNanos = System.nanoTime() + durationMs * 1_000_000L;
    }

    @Override
    public void hide() {
        this.alert = null;
        this.expiresAtNanos = 0L;
    }

    @Override
    public boolean isActive() {
        return alert != null && System.nanoTime() < expiresAtNanos;
    }

    /** 到期则清除并返回 {@code true}。 */
    public boolean expireIfNeeded() {
        if (alert != null && System.nanoTime() >= expiresAtNanos) {
            hide();
            return true;
        }
        return false;
    }

    public RedScreenAlert current() {
        return alert;
    }

    /** 画全屏红屏与提示文案。 */
    public void renderOverlay(GuiGraphics graphics) {
        RedScreenAlert current = alert;
        if (current == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        graphics.fill(0, 0, width, height, OVERLAY_ARGB);
        graphics.drawCenteredString(mc.font, TITLE, width / 2, height / 2 - 12, 0xFFFFFFFF);
        graphics.drawCenteredString(mc.font, Component.literal(current.reason()),
                width / 2, height / 2 + 12, 0xFFFFFFFF);
    }
}
