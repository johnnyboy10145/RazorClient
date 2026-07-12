package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.IntRangeSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.Random;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public final class KnockbackDelayModule extends Module {
    private final IntRangeSetting airDelay = new IntRangeSetting("delay (ms)", 300, 200, 0, 1000);
    private final NumberSetting chance = new NumberSetting("chance %", 0, 100, 1, 100);
    private final Random random = new Random();

    public static volatile long holdPacketsUntil = 0L;
    public static volatile int cachedPlayerId = -1;
    public static volatile boolean cachedOnGround = false;

    public KnockbackDelayModule() {
        super("Knockback Delay", "Buffers all incoming packets when hit, freezing the world until the delay expires", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(airDelay);
        addSetting(chance);
    }

    @Override
    protected void onDisable() {
        holdPacketsUntil = 0L;

        RazorClient client = RazorClient.getInstance();
        if (client != null) {
            client.getKnockbackDelayBuffer().flushAllIncoming();
        }
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) {
            return;
        }

        cachedPlayerId = minecraft.thePlayer.getEntityId();
        cachedOnGround = minecraft.thePlayer.onGround;
    }

    public void triggerDelay(boolean onGround) {
        long now = monotonicMillis();
        if (now < holdPacketsUntil) {
            return;
        }

        int low = airDelay.getLow();
        int high = airDelay.getHigh();
        long delayMs = high > low ? low + random.nextInt(high - low + 1) : (long) low;
        holdPacketsUntil = now + delayMs;
    }

    public boolean isHolding() {
        return isEnabled() && monotonicMillis() < holdPacketsUntil;
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    public NumberSetting getChance() {
        return chance;
    }

    public IntRangeSetting getAirDelay() {
        return airDelay;
    }

    @Override
    public String getHudInfo() {
        if (isHolding()) {
            return "Holding";
        }

        int low = airDelay.getLow();
        int high = airDelay.getHigh();
        return high > low ? low + "-" + high + "ms" : low + "ms";
    }

    @Override
    public int getHudInfoColor() {
        return isHolding() ? 0xFFFF5050 : super.getHudInfoColor();
    }
}
