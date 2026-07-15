package com.ouroboros.wildlife.hud;

import com.ouroboros.wildlife.fabric.BalancerHudPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Compact, fading overlay of the most recent server census sample. */
final class WildlifeHudOverlay {
    private static volatile Sample latest;
    private static boolean visible = true;

    private record Sample(BalancerHudPayload payload, long receivedAtMillis) {}

    private WildlifeHudOverlay() {}

    static void update(BalancerHudPayload payload) {
        latest = new Sample(payload, System.currentTimeMillis());
    }

    static void toggle() {
        visible = !visible;
    }

    static void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Sample sample = latest;
        if (!visible || sample == null) return;

        long cycleMillis = Math.max(1_000L, sample.payload().cycleSeconds() * 1_000L);
        long age = Math.max(0L, System.currentTimeMillis() - sample.receivedAtMillis());
        if (age >= cycleMillis * 2) return;
        float opacity = age <= cycleMillis
                ? 1.0F : 1.0F - (float) (age - cycleMillis) / cycleMillis;
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255)));

        String title = "Wildlife";
        String counts = "Wild " + sample.payload().wild() + " / " + sample.payload().target();
        String guardrails = "Streak " + sample.payload().streak()
                + "  Budget " + sample.payload().budgetLeft();
        Font font = Minecraft.getInstance().font;
        int width = Math.max(font.width(title), Math.max(font.width(counts), font.width(guardrails))) + 12;
        int height = font.lineHeight * 3 + 10;
        int x = graphics.guiWidth() - width - 8;
        int y = 8;

        int backgroundAlpha = alpha * 3 / 4;
        graphics.fill(x, y, x + width, y + height, backgroundAlpha << 24 | 0x102018);
        int titleColor = alpha << 24 | 0x8FE3A8;
        int textColor = alpha << 24 | 0xFFFFFF;
        graphics.text(font, title, x + 6, y + 5, titleColor, true);
        graphics.text(font, counts, x + 6, y + 5 + font.lineHeight, textColor, true);
        graphics.text(font, guardrails, x + 6, y + 5 + font.lineHeight * 2, textColor, true);
    }
}
