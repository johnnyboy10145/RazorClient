package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.ModuleResetReason;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import org.lwjgl.input.Keyboard;

public final class BlinkModule extends Module {
    private static final long STATUS_VISIBLE_MS = 750L;

    private final EnumSetting<Direction> direction = new EnumSetting<Direction>("Direction", Direction.values(), Direction.OUTBOUND);
    private final NumberSetting maximumDuration = new NumberSetting("Maximum Duration", 0, 10000, 100, 2500);
    private final BooleanSetting allowKeepAlives = new BooleanSetting("Allow Keep Alives", true);
    private final BooleanSetting disableOnAttack = new BooleanSetting("Disable On Attack", true);
    private final BooleanSetting disableOnBlockInteract = new BooleanSetting("Disable On Block Interact", false);
    private final BooleanSetting disableOnBlockDig = new BooleanSetting("Disable On Block Dig", false);

    private long enabledAt;
    private long statusUntil;
    private final AtomicBoolean outboundFlushRequested = new AtomicBoolean();
    private final AtomicBoolean inboundFlushRequested = new AtomicBoolean();
    private volatile boolean heldOutboundPackets;
    private volatile boolean heldInboundPackets;
    private volatile boolean disablePending;
    private volatile Packet<?> triggerBypassPacket;
    private volatile Status status = Status.HOLDING;
    private volatile Direction activeDirection = Direction.OUTBOUND;

    public BlinkModule() {
        super("Blink", "Holds inbound, outbound, or both packet directions until flushed.", Category.LAG_MODULES, Keyboard.KEY_NONE);
        addSetting(direction);
        addSetting(maximumDuration);
        addSetting(allowKeepAlives);
        addSetting(disableOnAttack);
        addSetting(disableOnBlockInteract);
        addSetting(disableOnBlockDig);
    }

    @Override
    protected void onEnable() {
        enabledAt = LagModuleSupport.now();
        statusUntil = 0L;
        outboundFlushRequested.set(false);
        inboundFlushRequested.set(false);
        heldOutboundPackets = false;
        heldInboundPackets = false;
        disablePending = false;
        triggerBypassPacket = null;
        activeDirection = direction.getValue();
        status = Status.HOLDING;
    }

    @Override
    protected void onDisable() {
        disablePending = false;
        triggerBypassPacket = null;
        if (status != Status.EXPIRED) status = Status.FLUSHING;
        requestFlush(activeDirection);
    }

    @Override
    public void onSessionReset(ModuleResetReason reason) {
        disableAndFlush();
    }

    @Override
    public void onInputContextLost(ModuleResetReason reason) {
        disableAndFlush();
    }

    @Override
    public void onClientTick() {
        if (disablePending) {
            disablePending = false;
            setEnabled(false);
            return;
        }
        if (!LagModuleSupport.inGame(Minecraft.getMinecraft())) {
            disableAndFlush();
            return;
        }

        Direction configured = direction.getValue();
        if (configured != activeDirection) {
            requestFlush(activeDirection);
            activeDirection = configured;
            setTransientStatus(Status.FLUSHING);
        }

        int limit = maximumDuration.getValue();
        if (limit > 0 && elapsedMs() >= limit) {
            status = Status.EXPIRED;
            setEnabled(false);
            return;
        }
        if (statusUntil > 0L && LagModuleSupport.now() >= statusUntil) {
            statusUntil = 0L;
            status = Status.HOLDING;
        }
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if ((disableOnAttack.isEnabled() && LagModuleSupport.isAttackPacket(packet))
            || (disableOnBlockInteract.isEnabled() && LagModuleSupport.isBlockInteractPacket(packet))
            || (disableOnBlockDig.isEnabled() && LagModuleSupport.isBlockDigPacket(packet))) {
            status = Status.FLUSHING;
            // Queue the triggering action behind the held lane. The next client tick disables
            // Blink and releases the complete lane in original order.
            triggerBypassPacket = packet;
            disablePending = true;
            outboundFlushRequested.set(true);
        }
    }

    @Override
    public boolean shouldHoldOutboundPacket(Packet<?> packet) {
        if (packet == triggerBypassPacket) {
            triggerBypassPacket = null;
            heldOutboundPackets = true;
            return true;
        }
        if (disablePending) return false;
        if (!activeDirection.outbound || bypassesProtocolPacket(packet)) return false;
        heldOutboundPackets = true;
        if (statusUntil == 0L) status = Status.HOLDING;
        return true;
    }

    @Override
    public boolean shouldBypassOutboundOrdering(Packet<?> packet) {
        return isEnabled() && !disablePending && activeDirection.outbound && bypassesProtocolPacket(packet);
    }

    @Override
    public boolean shouldHoldInboundPacket(Packet<?> packet) {
        if (!activeDirection.inbound) return false;
        heldInboundPackets = true;
        if (statusUntil == 0L) status = Status.HOLDING;
        return true;
    }

    @Override public int getOutboundPacketDelayPriority(Packet<?> packet) { return 60; }
    @Override public int getInboundPacketDelayPriority(Packet<?> packet) { return 60; }
    @Override public boolean isPacketDelayActive() { return !disablePending && (activeDirection.outbound || activeDirection.inbound); }
    @Override public boolean isOutboundPacketDelayActive() { return !disablePending && activeDirection.outbound; }
    @Override public boolean isInboundPacketDelayActive() { return !disablePending && activeDirection.inbound; }

    @Override
    public void onPacketDelayOverflow(boolean outbound) {
        if ((outbound && activeDirection.outbound) || (!outbound && activeDirection.inbound)) {
            setTransientStatus(Status.OVERFLOW);
        }
    }

    @Override
    public boolean consumeOutboundFlushRequest() {
        return outboundFlushRequested.getAndSet(false);
    }

    @Override
    public boolean consumeInboundFlushRequest() {
        return inboundFlushRequested.getAndSet(false);
    }

    @Override
    public String getHudInfo() {
        long elapsed = elapsedMs();
        int limit = maximumDuration.getValue();
        String timer = limit <= 0
            ? formatSeconds(elapsed)
            : formatSeconds(elapsed) + "/" + formatSeconds(limit);
        return activeDirection + " " + timer + (status == Status.HOLDING ? "" : " " + status.text);
    }

    private boolean bypassesProtocolPacket(Packet<?> packet) {
        return allowKeepAlives.isEnabled() && LagModuleSupport.isKeepAliveOrTransactionPacket(packet);
    }

    private void disableAndFlush() {
        if (isEnabled()) {
            setEnabled(false);
        } else {
            requestFlush(activeDirection);
        }
    }

    private void requestFlush(Direction heldDirection) {
        if (heldDirection.outbound || heldOutboundPackets) outboundFlushRequested.set(true);
        if (heldDirection.inbound || heldInboundPackets) inboundFlushRequested.set(true);
        heldOutboundPackets = false;
        heldInboundPackets = false;
    }

    private void setTransientStatus(Status next) {
        status = next;
        statusUntil = LagModuleSupport.now() + STATUS_VISIBLE_MS;
    }

    private long elapsedMs() {
        return Math.max(0L, LagModuleSupport.now() - enabledAt);
    }

    private String formatSeconds(long milliseconds) {
        return String.format(java.util.Locale.ROOT, "%.1fs", milliseconds / 1000.0D);
    }

    private enum Status {
        HOLDING("Holding"), FLUSHING("Flushing"), OVERFLOW("Overflow"), EXPIRED("Expired");
        private final String text;
        Status(String text) { this.text = text; }
    }

    private enum Direction {
        OUTBOUND("Outbound", true, false), INBOUND("Inbound", false, true), BOTH("Both", true, true);
        private final String text;
        private final boolean outbound;
        private final boolean inbound;
        Direction(String text, boolean outbound, boolean inbound) {
            this.text = text; this.outbound = outbound; this.inbound = inbound;
        }
        @Override public String toString() { return text; }
    }
}
