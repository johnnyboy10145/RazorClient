package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.NumberSetting;
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

    private int targetEntityId = -1;
    private long lastDeactivatedAt;
    private boolean inboundFlushRequested;

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
        inboundFlushRequested = false;
    }

    @Override
    protected void onDisable() {
        lastDeactivatedAt = LagModuleSupport.now();
        inboundFlushRequested = true;
        targetEntityId = -1;
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!LagModuleSupport.activeInGame(minecraft)) {
            inboundFlushRequested = true;
            targetEntityId = -1;
            return;
        }

        if (disableOnHit.isEnabled() && minecraft.thePlayer.hurtTime > 0) {
            inboundFlushRequested = true;
            targetEntityId = -1;
            lastDeactivatedAt = LagModuleSupport.now();
            return;
        }

        EntityPlayer target = LagModuleSupport.crosshairTarget(minecraft, targetDistance.getValue(), 180.0F);
        targetEntityId = target == null ? -1 : target.getEntityId();
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
        if (!inboundFlushRequested) {
            return false;
        }
        inboundFlushRequested = false;
        return true;
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
        LagModuleSupport.drawEntityBox(target, event, 0.65F, 0.35F, 1.0F);
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

        Entity entity = minecraft.theWorld.getEntityByID(targetEntityId);
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        EntityPlayer target = (EntityPlayer) entity;
        int hurtTimeMs = Math.max(0, target.hurtResistantTime) * 50;
        return hurtTimeMs <= maximumHurtTime.getValue();
    }
}
