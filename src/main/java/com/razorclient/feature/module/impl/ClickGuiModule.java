package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import org.lwjgl.input.Keyboard;

public final class ClickGuiModule extends Module {
    private static final int DEFAULT_CLASSIC_ACCENT_COLOR = ColorPreset.RAZOR_GREEN.getColor();
    private static ClickGuiModule instance;

    private final EnumSetting<ColorPreset> colorPreset = new EnumSetting<ColorPreset>(
        "Color Preset",
        ColorPreset.values(),
        ColorPreset.RAZOR_GREEN
    );
    private final BooleanSetting guiEffects = new BooleanSetting("GUI Effects", true);
    private final NumberSetting glowIntensity = new NumberSetting("Glow Intensity", 0, 120, 5, 80);
    private final BooleanSetting sweepAnimation = new BooleanSetting("Sweep Animation", true);
    private final BooleanSetting customCursor = new BooleanSetting("Custom Cursor", true);
    private final NumberSetting cursorScale = new NumberSetting("Cursor Scale", 40, 160, 5, 90);

    public ClickGuiModule() {
        super("ClickGUI", "Configure the ClickGUI", Category.CLIENT, Keyboard.KEY_RSHIFT);
        instance = this;
        addSetting(colorPreset);
        addSetting(guiEffects);
        addSetting(glowIntensity);
        addSetting(sweepAnimation);
        addSetting(customCursor);
        addSetting(cursorScale);
    }

    @Override
    public void toggle() {
        RazorClient client = RazorClient.getInstance();
        if (client != null) {
            client.toggleClickGui();
        }
    }

    @Override
    public boolean canBeUnbound() {
        return false;
    }

    public static int getAccentColor() {
        if (instance == null) {
            return DEFAULT_CLASSIC_ACCENT_COLOR;
        }
        return instance.colorPreset.getValue().getColor();
    }

    public static ClickGuiModule getInstance() {
        return instance;
    }

    public static int getLightAccentColor() {
        return blendColor(getAccentColor(), 0xFFFFFF, 0.52F);
    }

    public static int getDarkAccentColor() {
        return blendColor(getAccentColor(), 0x08111B, 0.48F);
    }

    public static boolean areGuiEffectsEnabled() {
        return instance == null || instance.guiEffects.isEnabled();
    }

    public static int getGlowIntensity() {
        return instance == null ? 80 : instance.glowIntensity.getValue();
    }

    public static boolean isSweepAnimationEnabled() {
        return instance == null || instance.sweepAnimation.isEnabled();
    }

    public static boolean isCustomCursorEnabled() {
        return instance == null || instance.customCursor.isEnabled();
    }

    public static int getCursorScale() {
        return instance == null ? 90 : instance.cursorScale.getValue();
    }

    public static int blendColor(int start, int end, float progress) {
        float amount = Math.max(0.0F, Math.min(1.0F, progress));
        int startR = (start >>> 16) & 255;
        int startG = (start >>> 8) & 255;
        int startB = start & 255;
        int endR = (end >>> 16) & 255;
        int endG = (end >>> 8) & 255;
        int endB = end & 255;
        int red = Math.round(startR + ((endR - startR) * amount));
        int green = Math.round(startG + ((endG - startG) * amount));
        int blue = Math.round(startB + ((endB - startB) * amount));
        return (red << 16) | (green << 8) | blue;
    }

    public enum ColorPreset {
        CLASSIC_BLUE("Classic Blue", 0x305CA8),
        RAZOR_GREEN("Razor Green", 0x2D9656),
        EXHIBIT_GREEN("Exhibit Green", 0x1E7F48),
        MINT_GREEN("Mint Green", 0x45C883),
        PURPLE("Purple", 0x8A5CFF),
        PINK("Pink", 0xFF5CA8),
        RED("Red", 0xE84855),
        ORANGE("Orange", 0xFF9F1C),
        YELLOW("Yellow", 0xFFD166),
        CYAN("Cyan", 0x2EC4B6),
        WHITE("White", 0xE8EAF1);

        private final String displayName;
        private final int color;

        ColorPreset(String displayName, int color) {
            this.displayName = displayName;
            this.color = color;
        }

        public int getColor() {
            return color;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
