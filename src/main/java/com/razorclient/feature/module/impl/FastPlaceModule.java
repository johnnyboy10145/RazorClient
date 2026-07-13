package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

public final class FastPlaceModule extends Module {
    private static final Field RIGHT_CLICK_DELAY = resolveDelayField();
    private final NumberSetting delay = new NumberSetting("Delay", 0, 4, 1, 0);
    private final BooleanSetting blocksOnly = new BooleanSetting("Blocks Only", true);

    public FastPlaceModule() {
        super("Fast Place", "Reduces the local delay between item placements.", Category.PLAYER, Keyboard.KEY_NONE);
        addSetting(delay);
        addSetting(blocksOnly);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (RIGHT_CLICK_DELAY == null || minecraft.thePlayer == null || minecraft.theWorld == null
                || minecraft.currentScreen != null || !minecraft.inGameHasFocus) return;
        ItemStack held = minecraft.thePlayer.getHeldItem();
        if (blocksOnly.isEnabled() && (held == null || !(held.getItem() instanceof ItemBlock))) return;
        setDelay(minecraft, delay.getValue());
    }

    @Override
    protected void onDisable() {
        setDelay(Minecraft.getMinecraft(), 4);
    }

    @Override
    public void onSessionReset() {
        setDelay(Minecraft.getMinecraft(), 4);
    }

    @Override
    public void onInputContextLost() {
        setDelay(Minecraft.getMinecraft(), 4);
    }

    @Override
    public String getHudInfo() {
        return RIGHT_CLICK_DELAY == null ? "Unavailable" : Integer.toString(delay.getValue());
    }

    private static void setDelay(Minecraft minecraft, int value) {
        if (RIGHT_CLICK_DELAY == null || minecraft == null) return;
        try {
            RIGHT_CLICK_DELAY.setInt(minecraft, Math.max(0, Math.min(4, value)));
        } catch (IllegalAccessException ignored) {
        }
    }

    private static Field resolveDelayField() {
        try {
            Field field = ReflectionHelper.findField(Minecraft.class, "field_71467_ac", "rightClickDelayTimer");
            field.setAccessible(true);
            return field;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
