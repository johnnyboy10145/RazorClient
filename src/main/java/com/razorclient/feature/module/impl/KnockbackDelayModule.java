package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.IntRangeSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import org.lwjgl.input.Keyboard;

public final class KnockbackDelayModule extends Module {
    private final IntRangeSetting airDelay = new IntRangeSetting("delay (ms)", 300, 200, 0, 1000);
    private final NumberSetting chance = new NumberSetting("chance %", 0, 100, 1, 100);
    private final Random random = getScope().getRandom();

    private volatile long holdPacketsUntil;
    private volatile int cachedPlayerId = -1;
    private final AtomicBoolean inboundFlushRequested = new AtomicBoolean();

    public KnockbackDelayModule() {
        super("Knockback Delay", "Buffers all incoming packets when hit, freezing the world until the delay expires", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(airDelay);
        addSetting(chance);
    }

    @Override
    protected void onEnable() {
        holdPacketsUntil = 0L;
        inboundFlushRequested.set(false);
    }

    @Override
    protected void onDisable() {
        holdPacketsUntil = 0L;
        inboundFlushRequested.set(true);
    }

    @Override
    public void onSessionReset() {
        holdPacketsUntil = 0L;
        cachedPlayerId = -1;
        inboundFlushRequested.set(true);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) {
            return;
        }

        cachedPlayerId = minecraft.thePlayer.getEntityId();
    }

    @Override
    public void onInboundPacket(Packet<?> packet) {
        if (isHolding() || !(packet instanceof S12PacketEntityVelocity)) {
            return;
        }

        S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
        if (cachedPlayerId == -1 || velocity.getEntityID() != cachedPlayerId) {
            return;
        }

        int percent = chance.getValue();
        if (percent >= 100 || (percent > 0 && random.nextInt(100) < percent)) {
            triggerDelay();
        }
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        long remaining = holdPacketsUntil - monotonicMillis();
        if (!isEnabled() || remaining <= 0L) {
            return 0;
        }
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    @Override
    public int getInboundPacketDelayPriority(Packet<?> packet) {
        return 80;
    }

    @Override
    public boolean isInboundPacketDelayActive() {
        return isHolding();
    }

    @Override
    public boolean consumeInboundFlushRequest() {
        return inboundFlushRequested.getAndSet(false);
    }

    private void triggerDelay() {
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
