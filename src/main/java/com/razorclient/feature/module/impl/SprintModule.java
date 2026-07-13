package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

public final class SprintModule extends Module {
    public SprintModule() {
        super("Sprint", "Automatically keeps you sprinting.", Category.MOVEMENT, Keyboard.KEY_NONE);
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            onDisable();
            return;
        }

        KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindSprint.getKeyCode(), true);
    }

    @Override
    protected void onDisable() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings != null) {
            KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }

    @Override
    public void onSessionReset() {
        onDisable();
    }

    @Override
    public void onInputContextLost() {
        onDisable();
    }

    @Override
    public String getHudInfo() {
        return "Auto";
    }
}
