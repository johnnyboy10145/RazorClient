package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.EnumSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import org.lwjgl.input.Keyboard;

public final class NoHitDelayModule extends Module {
    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.LEGIT);
    private boolean attackWasDown;

    public NoHitDelayModule() {
        super("No Hit Delay", "Removes the 1.8 missed-swing cooldown.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(mode);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!CombatModuleSupport.inGame(minecraft) || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            attackWasDown = false;
            return;
        }

        boolean attackDown = CombatModuleSupport.attackButtonDown(minecraft);
        EntityLivingBase target = CombatModuleSupport.crosshairLivingTarget(minecraft);
        if (!HitSelectModule.isSuppressingAttack() && (mode.getValue() == Mode.REGULAR || target != null)) {
            minecraft.leftClickCounter = 0;
        } else if (attackDown && !attackWasDown && minecraft.leftClickCounter > 0) {
            // Legit mode keeps the missed-hit delay while preserving local swing feedback.
            minecraft.thePlayer.swingItem();
        }
        attackWasDown = attackDown;
    }

    @Override
    protected void onDisable() {
        attackWasDown = false;
    }

    @Override
    public void onSessionReset() {
        attackWasDown = false;
    }

    @Override
    public void onInputContextLost() {
        attackWasDown = false;
    }

    @Override
    public String getHudInfo() {
        return mode.getValue().toString();
    }

    public enum Mode {
        REGULAR("Regular"),
        LEGIT("Legit");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
