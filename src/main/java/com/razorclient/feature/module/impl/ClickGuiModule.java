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
        return blendColor(getAccentColor(), getThemeBackgroundColor(), 0.48F);
    }

    private static ColorPreset getPreset() {
        return instance == null ? ColorPreset.RAZOR_GREEN : instance.colorPreset.getValue();
    }

    public static int getThemeBackgroundColor() { return getPreset().getBackground(); }
    public static int getThemeSurfaceColor() { return getPreset().getSurface(); }
    public static int getThemeRaisedColor() { return getPreset().getRaised(); }
    public static int getThemeHoverColor() { return getPreset().getHover(); }
    public static int getThemeBorderColor() { return getPreset().getBorder(); }
    public static int getThemeTextColor() { return getPreset().getText(); }
    public static int getThemeMutedTextColor() { return getPreset().getMutedText(); }

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
        CLASSIC_BLUE("Midnight", 0x4778D9, 0x060812, 0x0B1020, 0x111A31, 0x16233F, 0x22345A, 0xEAF0FF, 0x8592AC),
        RAZOR_GREEN("Razor", 0x2D9656, 0x050805, 0x0A100C, 0x0D1711, 0x132319, 0x173820, 0xE9EEE9, 0x85908A),
        EXHIBIT_GREEN("Exhibit", 0x1E7F48, 0x030704, 0x08110B, 0x0C1910, 0x11251A, 0x163A25, 0xE6EDE8, 0x78877E),
        MINT_GREEN("Emerald", 0x45C883, 0x050B08, 0x0A1610, 0x0E2017, 0x153025, 0x20513A, 0xE8F5ED, 0x83A092),
        PURPLE("Amethyst", 0x8A5CFF, 0x080611, 0x100B20, 0x17102D, 0x21183D, 0x39265F, 0xF0EBFF, 0x988CAD),
        PINK("Sakura", 0xE864A5, 0x10060C, 0x1B0C15, 0x27121E, 0x381A2A, 0x5A2942, 0xFFF0F7, 0xAD8B9B),
        RED("Crimson", 0xE84855, 0x100506, 0x1A0B0D, 0x261013, 0x37171B, 0x57252B, 0xFFF0F1, 0xAD898D),
        ORANGE("Ember", 0xF28C35, 0x0F0904, 0x1A1008, 0x25170C, 0x362214, 0x55351E, 0xFFF4E9, 0xAA927E),
        YELLOW("Gold", 0xDDB84A, 0x0D0B05, 0x171409, 0x211D0D, 0x302A14, 0x4F4522, 0xFFF9E7, 0xA49B7D),
        CYAN("Abyss", 0x2EC4B6, 0x040B0C, 0x081517, 0x0C1E21, 0x112C30, 0x1A484D, 0xE8FBFA, 0x7F9FA0),
        WHITE("Monochrome", 0xE8EAF1, 0x08090B, 0x101216, 0x171A1F, 0x22262D, 0x363C46, 0xF2F3F5, 0x92979F);

        private final String displayName;
        private final int color;
        private final int background;
        private final int surface;
        private final int raised;
        private final int hover;
        private final int border;
        private final int text;
        private final int mutedText;

        ColorPreset(String displayName, int color, int background, int surface, int raised, int hover, int border, int text, int mutedText) {
            this.displayName = displayName;
            this.color = color;
            this.background = background;
            this.surface = surface;
            this.raised = raised;
            this.hover = hover;
            this.border = border;
            this.text = text;
            this.mutedText = mutedText;
        }

        public int getColor() {
            return color;
        }

        public int getBackground() { return background; }
        public int getSurface() { return surface; }
        public int getRaised() { return raised; }
        public int getHover() { return hover; }
        public int getBorder() { return border; }
        public int getText() { return text; }
        public int getMutedText() { return mutedText; }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
