package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;

public final class LagRangeModule extends Module {
    private static final int MAX_POSITION_SAMPLES = 64;

    private final NumberSetting maximumDelay = new NumberSetting("Maximum Delay", 0, 1000, 10, 180);
    private final DecimalSetting activationRange = new DecimalSetting("Activation Range", 1.0D, 8.0D, 0.1D, 4.0D);
    private final BooleanSetting flushOnSprintReset = new BooleanSetting("Flush On Sprint Reset", true);
    private final BooleanSetting flushOnSplashPotion = new BooleanSetting("Flush On Splash Potion", true);
    private final BooleanSetting realPositionIndicator = new BooleanSetting("Real Position Indicator", true);
    private final BooleanSetting holdingWeapon = new BooleanSetting("Holding Weapon", false);

    private volatile int targetEntityId = -1;
    private volatile boolean weaponHeld;
    private final AtomicBoolean outboundFlushRequested = new AtomicBoolean();
    private volatile Packet<?> flushTriggerPacket;
    private final Object positionLock = new Object();
    private final Deque<MovementSample> pendingPositions = new ArrayDeque<MovementSample>();
    private volatile LagModuleSupport.ServerPosition serverPosition;
    private volatile int trackedPlayerId = -1;

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
        targetEntityId = -1;
        weaponHeld = false;
        outboundFlushRequested.set(false);
        flushTriggerPacket = null;
        resetPositionTracking();
    }

    @Override
    protected void onDisable() {
        targetEntityId = -1;
        weaponHeld = false;
        outboundFlushRequested.set(true);
        flushTriggerPacket = null;
        clearPositionTracking();
    }

    @Override
    public void onSessionReset() {
        targetEntityId = -1;
        weaponHeld = false;
        outboundFlushRequested.set(true);
        flushTriggerPacket = null;
        clearPositionTracking();
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        int previousTargetId = targetEntityId;
        EntityPlayer nextTarget = LagModuleSupport.activeInGame(minecraft)
            ? LagModuleSupport.closestCombatTarget(minecraft, activationRange.getValue())
            : null;
        targetEntityId = nextTarget == null ? -1 : nextTarget.getEntityId();
        weaponHeld = LagModuleSupport.inGame(minecraft) && LagModuleSupport.holdingWeapon(minecraft);
        if (targetEntityId == -1 && previousTargetId != -1) {
            outboundFlushRequested.set(true);
        }
        if (!LagModuleSupport.inGame(minecraft)) {
            clearPositionTracking();
            return;
        }
        if (serverPosition == null) {
            resetPositionTracking();
        }
        advanceReleasedPositions(LagModuleSupport.now(), false);
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if ((flushOnSprintReset.isEnabled() && LagModuleSupport.sprintResetPacket(packet))
            || (flushOnSplashPotion.isEnabled() && LagModuleSupport.splashPotionUse(packet))) {
            outboundFlushRequested.set(true);
            flushTriggerPacket = packet;
        }
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        if (packet == flushTriggerPacket) {
            flushTriggerPacket = null;
            return 1;
        }
        int delay = maximumDelay.getValue() <= 0
            || targetEntityId == -1
            || !LagModuleSupport.isMovementPacket(packet)
            || (holdingWeapon.isEnabled() && !weaponHeld)
            ? 0
            : maximumDelay.getValue();
        trackMovement(packet, delay);
        return delay;
    }

    @Override
    public int getOutboundPacketDelayPriority(Packet<?> packet) {
        return 90;
    }

    @Override
    public boolean isOutboundPacketDelayActive() {
        return targetEntityId != -1;
    }

    @Override
    public boolean consumeOutboundFlushRequest() {
        boolean requested = outboundFlushRequested.getAndSet(false);
        if (requested) {
            advanceReleasedPositions(LagModuleSupport.now(), true);
        }
        return requested;
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!realPositionIndicator.isEnabled()
            || minecraft == null
            || minecraft.gameSettings == null
            || minecraft.gameSettings.thirdPersonView == 0
            || !LagModuleSupport.inGame(minecraft)
            || targetEntityId == -1) {
            return;
        }
        EntityPlayer target = minecraft.theWorld.getEntityByID(targetEntityId) instanceof EntityPlayer
            ? (EntityPlayer) minecraft.theWorld.getEntityByID(targetEntityId) : null;
        if (target == null) return;
        LagModuleSupport.ServerPosition position = serverPosition;
        if (position == null || position.entityId != minecraft.thePlayer.getEntityId()) {
            return;
        }
        LagModuleSupport.drawEntityBoxAt(
            minecraft.thePlayer,
            position.x,
            position.y,
            position.z,
            event,
            0.35F,
            0.75F,
            1.0F
        );
    }

    @Override
    public String getHudInfo() {
        return targetEntityId == -1 ? maximumDelay.getValue() + "ms" : "Holding";
    }

    @Override
    public int getHudInfoColor() {
        return targetEntityId == -1 ? super.getHudInfoColor() : 0xFF58C8FF;
    }

    private void trackMovement(Packet<?> packet, int delay) {
        if (!(packet instanceof C03PacketPlayer)) {
            return;
        }
        C03PacketPlayer movement = (C03PacketPlayer) packet;
        if (!movement.isMoving()) {
            return;
        }

        int entityId = trackedPlayerId;
        if (entityId < 0) {
            return;
        }
        LagModuleSupport.ServerPosition position = new LagModuleSupport.ServerPosition(
            entityId,
            movement.getPositionX(),
            movement.getPositionY(),
            movement.getPositionZ()
        );
        long now = LagModuleSupport.now();
        synchronized (positionLock) {
            if (delay <= 0 && pendingPositions.isEmpty()) {
                serverPosition = position;
                return;
            }
            if (pendingPositions.size() >= MAX_POSITION_SAMPLES) {
                MovementSample oldest = pendingPositions.removeFirst();
                serverPosition = oldest.position;
            }
            pendingPositions.addLast(new MovementSample(position, now + Math.max(0, delay)));
        }
    }

    private void advanceReleasedPositions(long now, boolean flushAll) {
        synchronized (positionLock) {
            while (!pendingPositions.isEmpty()) {
                MovementSample sample = pendingPositions.peekFirst();
                if (!flushAll && sample.releaseAt > now) {
                    break;
                }
                serverPosition = pendingPositions.removeFirst().position;
            }
        }
    }

    private void resetPositionTracking() {
        Minecraft minecraft = Minecraft.getMinecraft();
        LagModuleSupport.ServerPosition initial = LagModuleSupport.inGame(minecraft)
            ? new LagModuleSupport.ServerPosition(
                minecraft.thePlayer.getEntityId(),
                minecraft.thePlayer.posX,
                minecraft.thePlayer.posY,
                minecraft.thePlayer.posZ
            )
            : null;
        synchronized (positionLock) {
            pendingPositions.clear();
            serverPosition = initial;
            trackedPlayerId = initial == null ? -1 : initial.entityId;
        }
    }

    private void clearPositionTracking() {
        synchronized (positionLock) {
            pendingPositions.clear();
            serverPosition = null;
            trackedPlayerId = -1;
        }
    }

    private static final class MovementSample {
        private final LagModuleSupport.ServerPosition position;
        private final long releaseAt;

        private MovementSample(LagModuleSupport.ServerPosition position, long releaseAt) {
            this.position = position;
            this.releaseAt = releaseAt;
        }
    }
}
