package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public final class FullbrightModule extends Module {
    private final NumberSetting brightness = new NumberSetting("Brightness", 1, 15, 1, 10);
    private float previousGamma;
    private boolean captured;

    public FullbrightModule() {
        super("Fullbright", "Raises local gamma while preserving your previous value.", Category.RENDER, Keyboard.KEY_NONE);
        addSetting(brightness);
    }

    @Override
    protected void onEnable() {
        captureAndApply();
    }

    @Override
    public void onClientTick() {
        captureAndApply();
    }

    @Override
    protected void onDisable() {
        restoreGamma();
    }

    @Override
    public String getHudInfo() {
        return Integer.toString(brightness.getValue());
    }

    private void captureAndApply() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.gameSettings == null) return;
        if (!captured) {
            previousGamma = minecraft.gameSettings.gammaSetting;
            captured = true;
        }
        minecraft.gameSettings.gammaSetting = brightness.getValue();
    }

    private void restoreGamma() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (captured && minecraft != null && minecraft.gameSettings != null) {
            minecraft.gameSettings.gammaSetting = previousGamma;
        }
        captured = false;
    }
}
