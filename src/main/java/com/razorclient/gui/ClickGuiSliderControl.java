package com.razorclient.gui;

import com.razorclient.feature.module.impl.ClickGuiModule;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.IntRangeSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.feature.setting.Setting;
import net.minecraft.client.gui.Gui;

/** Shared renderer and input mapping for numeric and range settings. */
final class ClickGuiSliderControl {
    private ClickGuiSliderControl() {
    }

    static boolean supports(Setting setting) {
        return setting instanceof NumberSetting || setting instanceof DecimalSetting || setting instanceof IntRangeSetting;
    }

    static void draw(Setting setting, int x, int y, int width, int accent, int darkAccent) {
        Gui.drawRect(x, y, x + width, y + 2, 0xFF000000 | GuiTheme.pageBackground());
        if (setting instanceof IntRangeSetting) {
            IntRangeSetting range = (IntRangeSetting) setting;
            int lowX = x + Math.round(width * progress(range.getLow(), range.getMin(), range.getMax()));
            int highX = x + Math.round(width * progress(range.getHigh(), range.getMin(), range.getMax()));
            Gui.drawRect(lowX, y, highX, y + 2, 0xFF000000 | accent);
            Gui.drawRect(lowX - 1, y - 2, lowX + 2, y + 4, 0xFF000000 | darkAccent);
            Gui.drawRect(highX - 1, y - 2, highX + 2, y + 4, 0xFF000000 | accent);
            return;
        }

        float valueProgress;
        if (setting instanceof NumberSetting) {
            NumberSetting number = (NumberSetting) setting;
            valueProgress = progress(number.getValue(), number.getMin(), number.getMax());
        } else {
            DecimalSetting decimal = (DecimalSetting) setting;
            valueProgress = progress(decimal.getValue(), decimal.getMin(), decimal.getMax());
        }
        int fillX = x + Math.round(width * valueProgress);
        Gui.drawRect(x, y, fillX, y + 2, 0xFF000000 | accent);
        if (ClickGuiModule.areGuiEffectsEnabled()) {
            Gui.drawRect(x, y - 1, fillX, y, GuiTheme.withAlpha(ClickGuiModule.getLightAccentColor(), 70));
        }
        Gui.drawRect(fillX - 1, y - 2, fillX + 2, y + 4, 0xFF000000 | accent);
    }

    static boolean isLowHandleClosest(Setting setting, int mouseX, int x, int width) {
        if (!(setting instanceof IntRangeSetting)) {
            return false;
        }
        IntRangeSetting range = (IntRangeSetting) setting;
        int lowX = x + Math.round(width * progress(range.getLow(), range.getMin(), range.getMax()));
        int highX = x + Math.round(width * progress(range.getHigh(), range.getMin(), range.getMax()));
        return Math.abs(mouseX - lowX) <= Math.abs(mouseX - highX);
    }

    static void update(Setting setting, int mouseX, int x, int width, boolean lowHandle) {
        float valueProgress = width <= 0 ? 0.0F : (mouseX - x) / (float) width;
        valueProgress = Math.max(0.0F, Math.min(1.0F, valueProgress));
        if (setting instanceof NumberSetting) {
            NumberSetting number = (NumberSetting) setting;
            int step = Math.max(1, number.getStep());
            float raw = number.getMin() + (valueProgress * (number.getMax() - number.getMin()));
            number.setValue(Math.round(raw / step) * step);
        } else if (setting instanceof DecimalSetting) {
            DecimalSetting decimal = (DecimalSetting) setting;
            double step = decimal.getStep() <= 0.0D ? 1.0D : decimal.getStep();
            double raw = decimal.getMin() + (valueProgress * (decimal.getMax() - decimal.getMin()));
            decimal.setValue(Math.round(raw / step) * step);
        } else if (setting instanceof IntRangeSetting) {
            IntRangeSetting range = (IntRangeSetting) setting;
            int value = Math.round(range.getMin() + (valueProgress * (range.getMax() - range.getMin())));
            if (lowHandle) {
                range.setLow(value, true);
            } else {
                range.setHigh(value, true);
            }
        }
    }

    private static float progress(double value, double min, double max) {
        if (max <= min) {
            return 0.0F;
        }
        return (float) Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
    }
}
