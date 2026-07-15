package com.razorclient.feature.setting;

import com.razorclient.config.ConfigManager;

public final class IntRangeSetting extends Setting {
    private final int min;
    private final int max;
    private volatile int low;
    private volatile int high;

    public IntRangeSetting(String name, int low, int high, int min, int max) {
        super(name);
        this.min = min;
        this.max = max;
        setRange(low, high, false);
    }

    public int getLow() {
        return low;
    }

    public int getHigh() {
        return high;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public void setLow(int value, boolean save) {
        low = Math.min(clamp(value), high);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    public void setHigh(int value, boolean save) {
        high = Math.max(clamp(value), low);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    public void setRange(int low, int high) {
        setRange(low, high, true);
    }

    public void setRange(int low, int high, boolean save) {
        int clampedLow = clamp(low);
        int clampedHigh = clamp(high);
        this.low = Math.min(clampedLow, clampedHigh);
        this.high = Math.max(clampedLow, clampedHigh);
        if (save) {
            ConfigManager.saveActiveConfig();
        }
    }

    @Override
    public String getValueText() {
        return low + " - " + high;
    }

    private int clamp(int input) {
        return Math.max(min, Math.min(max, input));
    }
}
