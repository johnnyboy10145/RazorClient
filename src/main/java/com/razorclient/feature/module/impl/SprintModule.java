package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.runtime.ResourceArbiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class SprintModule extends Module {
    private ResourceArbiter.Lease sprintLease;
    private final Runnable restoreSprint = new Runnable() {
        @Override public void run() { restorePhysicalSprint(); }
    };

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

        sprintLease = getScope().acquire(ResourceArbiter.Resource.SPRINT_INPUT, 10, 2, restoreSprint);
        if (sprintLease != null) {
            KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindSprint.getKeyCode(), true);
        }
    }

    @Override
    protected void onDisable() {
        if (sprintLease != null) sprintLease.close();
        sprintLease = null;
    }

    @Override
    public void onSessionReset() {
        sprintLease = null;
    }

    @Override
    public void onInputContextLost() {
        sprintLease = null;
    }

    @Override
    public String getHudInfo() {
        return "Auto";
    }

    private boolean isPhysicallyDown(int keyCode) {
        if (keyCode >= 0) {
            return Keyboard.isCreated() && Keyboard.isKeyDown(keyCode);
        }
        int mouseButton = keyCode + 100;
        return mouseButton >= 0 && Mouse.isCreated() && Mouse.isButtonDown(mouseButton);
    }

    private void restorePhysicalSprint() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.gameSettings == null) return;
        int keyCode = minecraft.gameSettings.keyBindSprint.getKeyCode();
        KeyBinding.setKeyBindState(keyCode, isPhysicallyDown(keyCode));
    }
}
