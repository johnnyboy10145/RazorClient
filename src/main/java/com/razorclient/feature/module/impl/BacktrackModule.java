package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;

public final class BacktrackModule extends Module {
    private final DecimalSetting targetDistance = new DecimalSetting("Target Distance", 1.0D, 6.0D, 0.1D, 4.0D);
    private final NumberSetting maximumDelay = new NumberSetting("Maximum Delay", 0, 1000, 10, 200);
    private final NumberSetting maximumHurtTime = new NumberSetting("Maximum Hurt Time", 0, 1000, 25, 500);
    private final NumberSetting cooldown = new NumberSetting("Cooldown", 0, 5000, 50, 750);
    private final BooleanSetting realPositionIndicator = new BooleanSetting("Real Position Indicator", true);
    private final BooleanSetting disableOnHit = new BooleanSetting("Disable On Hit", true);
    private final BooleanSetting holdingWeapon = new BooleanSetting("Holding Weapon", false);

    private volatile int targetEntityId = -1;
    private volatile long lastDeactivatedAt;
    private final AtomicBoolean inboundFlushRequested = new AtomicBoolean();
    private final AtomicReference<LagModuleSupport.ServerPosition> realPosition =
        new AtomicReference<LagModuleSupport.ServerPosition>();
    private volatile int targetHurtTimeMs;

    public BacktrackModule() {
        super("Backtrack", "Delays target position packets while they are near the edge of range.", Category.LAG_MODULES, Keyboard.KEY_NONE);
        addSetting(targetDistance);
        addSetting(maximumDelay);
        addSetting(maximumHurtTime);
        addSetting(cooldown);
        addSetting(realPositionIndicator);
        addSetting(disableOnHit);
        addSetting(holdingWeapon);
    }

    @Override
    protected void onEnable() {
        targetEntityId = -1;
        realPosition.set(null);
        inboundFlushRequested.set(false);
    }

    @Override
    protected void onDisable() {
        lastDeactivatedAt = LagModuleSupport.now();
        inboundFlushRequested.set(true);
        targetEntityId = -1;
        realPosition.set(null);
    }

    @Override
    public void onSessionReset() {
        inboundFlushRequested.set(true);
        targetEntityId = -1;
        realPosition.set(null);
        lastDeactivatedAt = LagModuleSupport.now();
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!LagModuleSupport.activeInGame(minecraft)) {
            if (targetEntityId != -1) {
                inboundFlushRequested.set(true);
            }
            targetEntityId = -1;
            realPosition.set(null);
            return;
        }

        if (disableOnHit.isEnabled() && minecraft.thePlayer.hurtTime > 0) {
            if (targetEntityId != -1) {
                inboundFlushRequested.set(true);
                lastDeactivatedAt = LagModuleSupport.now();
            }
            targetEntityId = -1;
            realPosition.set(null);
            return;
        }

        EntityPlayer target = LagModuleSupport.crosshairTarget(minecraft, targetDistance.getValue(), 180.0F);
        int previousTargetId = targetEntityId;
        int nextTargetId = target == null ? -1 : target.getEntityId();
        if (previousTargetId != nextTargetId) {
            realPosition.set(LagModuleSupport.entityServerPosition(target));
        }
        targetEntityId = nextTargetId;
        targetHurtTimeMs = target == null ? 0 : Math.max(0, target.hurtResistantTime) * 50;
        if (previousTargetId != -1 && previousTargetId != targetEntityId) {
            inboundFlushRequested.set(true);
            lastDeactivatedAt = LagModuleSupport.now();
        }
    }

    @Override
    public void onInboundPacket(Packet<?> packet) {
        int entityId = LagModuleSupport.getPacketEntityId(packet);
        if (entityId < 0 || entityId != targetEntityId || !LagModuleSupport.isEntityPositionPacket(packet)) {
            return;
        }

        while (true) {
            LagModuleSupport.ServerPosition previous = realPosition.get();
            LagModuleSupport.ServerPosition next = LagModuleSupport.decodeServerPosition(packet, previous);
            if (next == null || next.entityId != targetEntityId) {
                return;
            }
            if (realPosition.compareAndSet(previous, next)) {
                return;
            }
        }
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        if (!shouldDelay(packet)) {
            return 0;
        }
        return maximumDelay.getValue();
    }

    @Override
    public int getInboundPacketDelayPriority(Packet<?> packet) {
        return 90;
    }

    @Override
    public boolean isInboundPacketDelayActive() {
        return targetEntityId != -1;
    }

    @Override
    public boolean consumeInboundFlushRequest() {
        return inboundFlushRequested.getAndSet(false);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!realPositionIndicator.isEnabled() || targetEntityId == -1) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!LagModuleSupport.inGame(minecraft)) {
            return;
        }
        Entity entity = minecraft.theWorld.getEntityByID(targetEntityId);
        if (!(entity instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer target = (EntityPlayer) entity;
        LagModuleSupport.ServerPosition position = realPosition.get();
        if (position == null || position.entityId != targetEntityId) {
            return;
        }
        LagModuleSupport.drawEntityBoxAt(
            target,
            position.x,
            position.y,
            position.z,
            event,
            0.65F,
            0.35F,
            1.0F
        );
    }

    @Override
    public String getHudInfo() {
        return targetEntityId == -1 ? maximumDelay.getValue() + "ms" : "Holding";
    }

    @Override
    public int getHudInfoColor() {
        return targetEntityId == -1 ? super.getHudInfoColor() : 0xFFB07CFF;
    }

    private boolean shouldDelay(Packet<?> packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (targetEntityId == -1
            || maximumDelay.getValue() <= 0
            || !LagModuleSupport.isEntityPositionPacket(packet)
            || LagModuleSupport.getPacketEntityId(packet) != targetEntityId
            || LagModuleSupport.now() - lastDeactivatedAt < cooldown.getValue()
            || (holdingWeapon.isEnabled() && !LagModuleSupport.holdingWeapon(minecraft))) {
            return false;
        }

        return targetHurtTimeMs <= maximumHurtTime.getValue();
    }
}
