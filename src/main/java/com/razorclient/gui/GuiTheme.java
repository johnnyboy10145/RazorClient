package com.razorclient.gui;

import com.razorclient.feature.module.impl.ClickGuiModule;

public final class GuiTheme {
    public static final int BACKGROUND = 0x050805;
    public static final int SURFACE = 0x0A100C;
    public static final int SURFACE_RAISED = 0x0D1711;
    public static final int SURFACE_HOVER = 0x132319;
    public static final int BORDER = 0x173820;
    public static final int TEXT = 0xE9EEE9;
    public static final int MUTED_TEXT = 0x85908A;
    public static final int RAZOR_GREEN = 0x2D9656;

    private GuiTheme() {
    }

    public static int accent() {
        return ClickGuiModule.getAccentColor();
    }

    public static int lightAccent() {
        return ClickGuiModule.getLightAccentColor();
    }

    public static int darkAccent() {
        return ClickGuiModule.getDarkAccentColor();
    }

    public static int pageBackground() {
        return BACKGROUND;
    }

    public static int surface() {
        return SURFACE;
    }

    public static int raisedSurface() {
        return SURFACE_RAISED;
    }

    public static int hoverSurface() {
        return SURFACE_HOVER;
    }

    public static int border() {
        return BORDER;
    }

    public static int withAlpha(int rgb, int alpha) {
        return ((clamp(alpha) & 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static int blend(int start, int end, float progress) {
        return ClickGuiModule.blendColor(start, end, progress);
    }

    public static int blendArgb(int start, int end, float progress) {
        float amount = Math.max(0.0F, Math.min(1.0F, progress));
        int startA = (start >>> 24) & 255;
        int endA = (end >>> 24) & 255;
        int alpha = Math.round(startA + ((endA - startA) * amount));
        return withAlpha(blend(start, end, amount), alpha);
    }

    public static float easeOutCubic(float x) {
        float clamped = Math.max(0.0F, Math.min(1.0F, x));
        float inverse = 1.0F - clamped;
        return 1.0F - (inverse * inverse * inverse);
    }

    public static float pulse(long now, long periodMs) {
        long safePeriod = Math.max(1L, periodMs);
        double phase = (now % safePeriod) / (double) safePeriod;
        return (float) ((Math.sin(phase * Math.PI * 2.0D) + 1.0D) * 0.5D);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
