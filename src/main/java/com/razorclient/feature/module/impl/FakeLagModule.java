package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import org.lwjgl.input.Keyboard;

public final class FakeLagModule extends Module {
    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.STATIC);
    private final NumberSetting inboundDelay = new NumberSetting("Inbound Delay", 0, 1000, 10, 100);
    private final NumberSetting outboundDelay = new NumberSetting("Outbound Delay", 0, 1000, 10, 100);
    private final NumberSetting pulseHold = new NumberSetting("Pulse Hold", 50, 2000, 25, 350);
    private final NumberSetting pulseFlush = new NumberSetting("Pulse Flush", 50, 2000, 25, 150);
    private final BooleanSetting realtimeDamage = new BooleanSetting("Realtime Damage", true);
    private final BooleanSetting holdingWeapon = new BooleanSetting("Holding Weapon", false);
    private final BooleanSetting requireAttack = new BooleanSetting("Require Attack", false);
    private final BooleanSetting inGameOnly = new BooleanSetting("In Game Only", true);

    private volatile long enabledAt;
    private volatile long lastAttackAt;
    private final AtomicBoolean flushRequested = new AtomicBoolean();
    private volatile boolean delayActive;
    private volatile boolean pulseHoldActive;

    public FakeLagModule() {
        super("Fake Lag", "Adds controlled inbound and outbound packet latency.", Category.LAG_MODULES, Keyboard.KEY_NONE);
        addSetting(mode);
        addSetting(inboundDelay);
        addSetting(outboundDelay);
        addSetting(pulseHold);
        addSetting(pulseFlush);
        addSetting(realtimeDamage);
        addSetting(holdingWeapon);
        addSetting(requireAttack);
        addSetting(inGameOnly);
    }

    @Override
    protected void onEnable() {
        enabledAt = LagModuleSupport.now();
        lastAttackAt = 0L;
        flushRequested.set(false);
        delayActive = false;
        pulseHoldActive = false;
    }

    @Override
    protected void onDisable() {
        flushRequested.set(true);
        delayActive = false;
        pulseHoldActive = false;
    }

    @Override
    public void onSessionReset() {
        flushRequested.set(true);
        delayActive = false;
        pulseHoldActive = false;
        enabledAt = LagModuleSupport.now();
        lastAttackAt = 0L;
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if (LagModuleSupport.isAttackPacket(packet)) {
            lastAttackAt = LagModuleSupport.now();
        }
    }

    @Override
    public void onClientTick() {
        boolean wasActive = delayActive;
        boolean wasHolding = pulseHoldActive;
        delayActive = conditionsPass();
        pulseHoldActive = delayActive && mode.getValue() == Mode.PULSE && pulseHolding();
        if ((wasActive && !delayActive) || (wasHolding && !pulseHoldActive)) {
            flushRequested.set(true);
        }
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        if (LagModuleSupport.isKeepAliveOrTransactionPacket(packet)) return 0;
        return shouldDelay(false, packet) && mode.getValue() == Mode.STATIC ? outboundDelay.getValue() : 0;
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        if (LagModuleSupport.isInboundKeepAliveOrTransactionPacket(packet)
                || (realtimeDamage.isEnabled() && LagModuleSupport.isDamageStatus(packet))) {
            return 0;
        }
        return shouldDelay(true, packet) && mode.getValue() == Mode.STATIC ? inboundDelay.getValue() : 0;
    }

    @Override
    public boolean shouldHoldOutboundPacket(Packet<?> packet) {
        return !LagModuleSupport.isKeepAliveOrTransactionPacket(packet)
            && mode.getValue() == Mode.PULSE
            && pulseHoldActive
            && outboundDelay.getValue() > 0;
    }

    @Override
    public boolean shouldBypassOutboundOrdering(Packet<?> packet) {
        return LagModuleSupport.isKeepAliveOrTransactionPacket(packet);
    }

    @Override
    public boolean shouldHoldInboundPacket(Packet<?> packet) {
        return mode.getValue() == Mode.PULSE
            && pulseHoldActive
            && inboundDelay.getValue() > 0
            && !LagModuleSupport.isInboundKeepAliveOrTransactionPacket(packet)
            && !(realtimeDamage.isEnabled() && LagModuleSupport.isDamageStatus(packet));
    }

    @Override
    public int getOutboundPacketDelayPriority(Packet<?> packet) {
        return 10;
    }

    @Override
    public int getInboundPacketDelayPriority(Packet<?> packet) {
        return 10;
    }

    @Override
    public boolean isPacketDelayActive() {
        return delayActive;
    }

    @Override
    public boolean consumeFlushRequest() {
        return flushRequested.getAndSet(false);
    }

    @Override
    public String getHudInfo() {
        return mode.getValue() + " " + inboundDelay.getValue() + "/" + outboundDelay.getValue() + "ms";
    }

    private boolean shouldDelay(boolean inbound, Packet<?> packet) {
        if (!delayActive) return false;
        return inbound ? inboundDelay.getValue() > 0 : outboundDelay.getValue() > 0;
    }

    private boolean pulseHolding() {
        long cycle = Math.max(1L, pulseHold.getValue() + pulseFlush.getValue());
        long elapsed = (LagModuleSupport.now() - enabledAt) % cycle;
        return elapsed < pulseHold.getValue();
    }

    private boolean conditionsPass() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (inGameOnly.isEnabled() && !LagModuleSupport.activeInGame(minecraft)) {
            return false;
        }
        if (holdingWeapon.isEnabled() && !LagModuleSupport.holdingWeapon(minecraft)) {
            return false;
        }
        return !requireAttack.isEnabled() || LagModuleSupport.now() - lastAttackAt <= 1500L;
    }

    private enum Mode {
        STATIC("Static"),
        PULSE("Pulse");

        private final String text;

        Mode(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
