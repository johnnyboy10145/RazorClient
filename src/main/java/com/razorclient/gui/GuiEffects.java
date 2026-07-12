package com.razorclient.gui;

import net.minecraft.client.gui.Gui;

public final class GuiEffects {
    private GuiEffects() {
    }

    public static void drawSoftShadow(int x, int y, int width, int height) {
        Gui.drawRect(x + 4, y + 5, x + width + 4, y + height + 5, 0x30000000);
        Gui.drawRect(x + 2, y + 3, x + width + 2, y + height + 3, 0x52000000);
        Gui.drawRect(x, y + 1, x + width, y + height + 1, 0x2A000000);
    }

    public static void drawGlowPanel(int x, int y, int width, int height, float hover, int intensity) {
        int accent = GuiTheme.accent();
        int light = GuiTheme.lightAccent();
        int dark = GuiTheme.darkAccent();
        int alpha = Math.max(8, Math.min(96, Math.round(intensity * (0.12F + hover * 0.35F))));
        int rimAlpha = Math.max(28, Math.min(128, alpha + 26));

        drawSoftShadow(x, y, width, height);
        Gui.drawRect(x - 3, y - 3, x + width + 3, y + height + 3, GuiTheme.withAlpha(accent, alpha / 10));
        Gui.drawRect(x - 1, y - 1, x + width + 1, y + height + 1, GuiTheme.withAlpha(GuiTheme.border(), 130));
        Gui.drawRect(x - 1, y - 1, x + width + 1, y, GuiTheme.withAlpha(light, rimAlpha));
        Gui.drawRect(x - 1, y + height, x + width + 1, y + height + 1, GuiTheme.withAlpha(dark, Math.max(22, alpha / 2)));
        Gui.drawRect(x - 1, y - 1, x, y + height + 1, GuiTheme.withAlpha(accent, rimAlpha));
        Gui.drawRect(x + width, y - 1, x + width + 1, y + height + 1, GuiTheme.withAlpha(dark, Math.max(22, alpha / 2)));
        drawCorner(x, y, width, height, light, Math.min(150, rimAlpha + 18));
    }

    public static void drawSweep(int x, int y, int width, int height, float hover, int intensity) {
        if (hover <= 0.01F || width <= 0 || height <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        float wave = GuiTheme.pulse(now, 3200L);
        int sweepWidth = Math.max(4, width / 10);
        int center = x + Math.round((width + sweepWidth * 2) * wave) - sweepWidth;
        int alpha = Math.max(4, Math.min(32, Math.round(intensity * hover * 0.32F)));
        int color = GuiTheme.withAlpha(GuiTheme.lightAccent(), alpha);
        int left = Math.max(x, center - sweepWidth);
        int right = Math.min(x + width, center + sweepWidth);
        if (right > left) {
            int sweepY = y + Math.max(1, height / 5);
            Gui.drawRect(left, sweepY, right, Math.min(y + height, sweepY + Math.max(1, height / 5)), color);
        }
    }

    public static void drawGradientFill(int x, int y, int width, int height, int baseAlpha) {
        int accent = GuiTheme.accent();
        int light = GuiTheme.lightAccent();
        int dark = GuiTheme.darkAccent();
        int alpha = Math.max(0, Math.min(30, baseAlpha));
        Gui.drawRect(x, y, x + width, y + height, 0xF2050805);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + Math.max(y + 2, y + height / 5), GuiTheme.withAlpha(light, alpha / 4));
        Gui.drawRect(x + 1, y + height - Math.max(2, height / 6), x + width - 1, y + height - 1, GuiTheme.withAlpha(dark, alpha / 2));
        Gui.drawRect(x + 2, y + 1, x + 3, y + height - 1, GuiTheme.withAlpha(accent, alpha));
    }

    private static void drawCorner(int x, int y, int width, int height, int color, int alpha) {
        int c = GuiTheme.withAlpha(color, alpha);
        int len = Math.max(6, Math.min(16, width / 4));
        Gui.drawRect(x - 1, y - 1, x + len, y, c);
        Gui.drawRect(x - 1, y - 1, x, y + len, c);
        Gui.drawRect(x + width - len, y - 1, x + width + 1, y, GuiTheme.withAlpha(color, alpha / 2));
        Gui.drawRect(x - 1, y + height, x + len, y + height + 1, GuiTheme.withAlpha(color, alpha / 2));
    }
}
