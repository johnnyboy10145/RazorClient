package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;

public final class LagRangeModule extends Module {
    private final NumberSetting maximumDelay = new NumberSetting("Maximum Delay", 0, 1000, 10, 180);
    private final DecimalSetting activationRange = new DecimalSetting("Activation Range", 1.0D, 8.0D, 0.1D, 4.0D);
    private final BooleanSetting flushOnSprintReset = new BooleanSetting("Flush On Sprint Reset", true);
    private final BooleanSetting flushOnSplashPotion = new BooleanSetting("Flush On Splash Potion", true);
    private final BooleanSetting realPositionIndicator = new BooleanSetting("Real Position Indicator", true);
    private final BooleanSetting holdingWeapon = new BooleanSetting("Holding Weapon", false);

    private EntityPlayer target;
    private boolean outboundFlushRequested;

    public LagRangeModule() {
        super("Lag Range", "Delays outbound movement while a target is near range.", Category.LAG_MODULES, Keyboard.KEY_NONE);
        addSetting(maximumDelay);
        addSetting(activationRange);
        addSetting(flushOnSprintReset);
        addSetting(flushOnSplashPotion);
        addSetting(realPositionIndicator);
        addSetting(holdingWeapon);
    }

    @Override
    protected void onEnable() {
        target = null;
        outboundFlushRequested = false;
    }

    @Override
    protected void onDisable() {
        target = null;
        outboundFlushRequested = true;
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        target = LagModuleSupport.activeInGame(minecraft)
            ? LagModuleSupport.closestCombatTarget(minecraft, activationRange.getValue())
            : null;
        if (target == null) {
            outboundFlushRequested = true;
        }
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if ((flushOnSprintReset.isEnabled() && LagModuleSupport.sprintResetPacket(packet))
            || (flushOnSplashPotion.isEnabled() && LagModuleSupport.splashPotionUse(packet))) {
            outboundFlushRequested = true;
        }
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        if (maximumDelay.getValue() <= 0
            || target == null
            || !LagModuleSupport.isMovementPacket(packet)
            || (holdingWeapon.isEnabled() && !LagModuleSupport.holdingWeapon(Minecraft.getMinecraft()))) {
            return 0;
        }
        return maximumDelay.getValue();
    }

    @Override
    public int getOutboundPacketDelayPriority(Packet<?> packet) {
        return 90;
    }

    @Override
    public boolean isOutboundPacketDelayActive() {
        return target != null;
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
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!realPositionIndicator.isEnabled()
            || minecraft == null
            || minecraft.gameSettings == null
            || minecraft.gameSettings.thirdPersonView == 0
            || !LagModuleSupport.inGame(minecraft)) {
            return;
        }
        LagModuleSupport.drawEntityBox(minecraft.thePlayer, event, 0.35F, 0.75F, 1.0F);
    }

    @Override
    public String getHudInfo() {
        return target == null ? maximumDelay.getValue() + "ms" : "Holding";
    }

    @Override
    public int getHudInfoColor() {
        return target == null ? super.getHudInfoColor() : 0xFF58C8FF;
    }
}
