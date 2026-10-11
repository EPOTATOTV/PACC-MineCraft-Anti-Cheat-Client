package com.potatotv.pacc.fabric.render;

import com.potatotv.pacc.client.core.redscreen.RedScreenAlert;
import com.potatotv.pacc.client.core.redscreen.RedScreenController;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Fabric 侧红屏渲染。检测命中后由引擎调用 {@link #show}，HUD 渲染回调负责画全屏红屏。
 */
public final class FabricRedScreenController implements RedScreenController {

    private static final int OVERLAY_ARGB = 0xE6FF0000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final Text TITLE = Text.translatable("pacc.redscreen.title");

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
    public void renderOverlay(DrawContext context) {
        RedScreenAlert current = alert;
        if (current == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        context.fill(0, 0, width, height, OVERLAY_ARGB);
        context.drawCenteredTextWithShadow(client.textRenderer, TITLE, width / 2, height / 2 - 12, TEXT_COLOR);
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(current.reason()),
                width / 2, height / 2 + 12, TEXT_COLOR);
    }
}
