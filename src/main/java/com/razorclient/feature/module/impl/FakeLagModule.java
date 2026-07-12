package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
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

    private long enabledAt;
    private long lastAttackAt;
    private boolean flushRequested;
    private boolean wasDelaying;

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
        flushRequested = false;
        wasDelaying = false;
    }

    @Override
    protected void onDisable() {
        flushRequested = true;
        wasDelaying = false;
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if (LagModuleSupport.isAttackPacket(packet)) {
            lastAttackAt = LagModuleSupport.now();
        }
    }

    @Override
    public void onClientTick() {
        boolean delaying = conditionsPass();
        if (wasDelaying && !delaying) {
            flushRequested = true;
        }
        wasDelaying = delaying;
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        return shouldDelay(false, packet) ? currentDelay(outboundDelay.getValue()) : 0;
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        if (realtimeDamage.isEnabled() && LagModuleSupport.isDamageStatus(packet)) {
            return 0;
        }
        return shouldDelay(true, packet) ? currentDelay(inboundDelay.getValue()) : 0;
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
        return conditionsPass();
    }

    @Override
    public boolean consumeFlushRequest() {
        if (!flushRequested) {
            return false;
        }
        flushRequested = false;
        return true;
    }

    @Override
    public String getHudInfo() {
        return mode.getValue() + " " + inboundDelay.getValue() + "/" + outboundDelay.getValue() + "ms";
    }

    private boolean shouldDelay(boolean inbound, Packet<?> packet) {
        if (!conditionsPass()) {
            return false;
        }
        if (mode.getValue() == Mode.PULSE && !pulseHolding()) {
            flushRequested = true;
            return false;
        }
        return inbound ? inboundDelay.getValue() > 0 : outboundDelay.getValue() > 0;
    }

    private int currentDelay(int baseDelay) {
        return mode.getValue() == Mode.PULSE ? Math.max(baseDelay, pulseHold.getValue()) : baseDelay;
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
