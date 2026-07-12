package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import org.lwjgl.input.Keyboard;

public final class BlinkModule extends Module {
    private static final int HOLD_DELAY_MS = 60_000;

    private final EnumSetting<Direction> direction = new EnumSetting<Direction>("Direction", Direction.values(), Direction.OUTBOUND);
    private final NumberSetting maximumDuration = new NumberSetting("Maximum Duration", 0, 10000, 100, 2500);
    private final BooleanSetting allowKeepAlives = new BooleanSetting("Allow Keep Alives", true);
    private final BooleanSetting disableOnAttack = new BooleanSetting("Disable On Attack", true);
    private final BooleanSetting disableOnBlockInteract = new BooleanSetting("Disable On Block Interact", false);
    private final BooleanSetting disableOnBlockDig = new BooleanSetting("Disable On Block Dig", false);

    private long enabledAt;
    private volatile boolean outboundFlushRequested;
    private volatile boolean inboundFlushRequested;
    private volatile boolean heldOutboundPackets;
    private volatile boolean heldInboundPackets;

    public BlinkModule() {
        super("Blink", "Holds selected packets until disabled or flushed.", Category.LAG_MODULES, Keyboard.KEY_NONE);
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
        outboundFlushRequested = false;
        inboundFlushRequested = false;
        heldOutboundPackets = false;
        heldInboundPackets = false;
    }

    @Override
    protected void onDisable() {
        requestFlush(direction.getValue());
    }

    @Override
    public void onClientTick() {
        if (!LagModuleSupport.inGame(Minecraft.getMinecraft())) {
            setEnabled(false);
            return;
        }

        if (maximumDuration.getValue() > 0 && elapsedMs() >= maximumDuration.getValue()) {
            setEnabled(false);
        }
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if ((disableOnAttack.isEnabled() && LagModuleSupport.isAttackPacket(packet))
            || (disableOnBlockInteract.isEnabled() && LagModuleSupport.isBlockInteractPacket(packet))
            || (disableOnBlockDig.isEnabled() && LagModuleSupport.isBlockDigPacket(packet))) {
            setEnabled(false);
        }
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        if (!direction.getValue().outbound) {
            return 0;
        }
        if (allowKeepAlives.isEnabled() && LagModuleSupport.isKeepAliveOrTransactionPacket(packet)) {
            return 0;
        }
        heldOutboundPackets = true;
        return HOLD_DELAY_MS;
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        if (!direction.getValue().inbound) {
            return 0;
        }
        heldInboundPackets = true;
        return HOLD_DELAY_MS;
    }

    @Override
    public int getOutboundPacketDelayPriority(Packet<?> packet) {
        return 60;
    }

    @Override
    public int getInboundPacketDelayPriority(Packet<?> packet) {
        return 60;
    }

    @Override
    public boolean isPacketDelayActive() {
        return direction.getValue().outbound || direction.getValue().inbound;
    }

    @Override
    public boolean isOutboundPacketDelayActive() {
        return direction.getValue().outbound;
    }

    @Override
    public boolean isInboundPacketDelayActive() {
        return direction.getValue().inbound;
    }

    @Override
    public boolean consumeOutboundFlushRequest() {
        if (!outboundFlushRequested) {
            return false;
        }
        outboundFlushRequested = false;
        return true;
    }

    @Override
    public boolean consumeInboundFlushRequest() {
        if (!inboundFlushRequested) {
            return false;
        }
        inboundFlushRequested = false;
        return true;
    }

    @Override
    public String getHudInfo() {
        int limit = maximumDuration.getValue();
        if (limit <= 0) {
            return direction.getValue().toString();
        }
        return direction.getValue().toString() + " " + formatSeconds(elapsedMs()) + "/" + formatSeconds(limit);
    }

    private void requestFlush(Direction heldDirection) {
        if (heldDirection.outbound || heldOutboundPackets) {
            outboundFlushRequested = true;
        }
        if (heldDirection.inbound || heldInboundPackets) {
            inboundFlushRequested = true;
        }
        heldOutboundPackets = false;
        heldInboundPackets = false;
    }

    private long elapsedMs() {
        return Math.max(0L, LagModuleSupport.now() - enabledAt);
    }

    private String formatSeconds(long milliseconds) {
        return String.format(java.util.Locale.ROOT, "%.1fs", milliseconds / 1000.0D);
    }

    private enum Direction {
        OUTBOUND("Outbound", true, false),
        INBOUND("Inbound", false, true),
        BOTH("Both", true, true);

        private final String text;
        private final boolean outbound;
        private final boolean inbound;

        Direction(String text, boolean outbound, boolean inbound) {
            this.text = text;
            this.outbound = outbound;
            this.inbound = inbound;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
