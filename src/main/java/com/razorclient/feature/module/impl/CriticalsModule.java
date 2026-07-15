package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.runtime.ResourceArbiter;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.Timer;
import org.lwjgl.input.Keyboard;

public final class CriticalsModule extends Module {
    private static final double PACKET_RISE = 0.0625D;
    private static final int MAX_TRANSACTION_PACKETS = 8;
    private static final int MAX_TRANSACTION_DELAY_MS = 50;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.PACKET);
    private final NumberSetting maximumDelay = new NumberSetting("Maximum Delay", 0, 500, 5, 150);
    private final NumberSetting timerSpeed = new NumberSetting("Timer Speed", 10, 100, 5, 50);
    private final NumberSetting chance = new NumberSetting("Chance", 0, 100, 1, 100);
    private final BooleanSetting holdingWeapon = new BooleanSetting("Holding Weapon", true);
    private final BooleanSetting mousePressed = new BooleanSetting("Mouse Pressed", true);

    private final Map<Packet<?>, Integer> transactionPackets =
        new IdentityHashMap<Packet<?>, Integer>();
    private final Random random = getScope().getRandom();
    private boolean timerOwned;
    private boolean airborneRollPassed;
    private boolean airborneRollSet;
    private int lastPacketCriticalTick = Integer.MIN_VALUE;
    private float previousTimerSpeed = 1.0F;
    private ResourceArbiter.Lease timerLease;
    private volatile String status = "Ready";

    public CriticalsModule() {
        super("Criticals", "Improves critical-hit timing without changing player movement.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(mode);
        addSetting(maximumDelay);
        addSetting(timerSpeed);
        addSetting(chance);
        addSetting(holdingWeapon);
        addSetting(mousePressed);
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if (mode.getValue() != Mode.PACKET || !CombatModuleSupport.isAttackPacket(packet)) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!canActivate(minecraft) || !rollChance()) {
            status = "Skipped";
            return;
        }
        if (!minecraft.thePlayer.onGround) {
            status = minecraft.thePlayer.fallDistance > 0.0F ? "Natural critical" : "Airborne";
            return;
        }
        int playerTick = minecraft.thePlayer.ticksExisted;
        if (lastPacketCriticalTick == playerTick || minecraft.getNetHandler() == null) {
            status = "Already primed";
            return;
        }
        lastPacketCriticalTick = playerTick;
        double x = minecraft.thePlayer.posX;
        double y = minecraft.thePlayer.posY;
        double z = minecraft.thePlayer.posZ;
        C03PacketPlayer rise = new C03PacketPlayer.C04PacketPlayerPosition(x, y + PACKET_RISE, z, false);
        C03PacketPlayer fall = new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, false);
        int delay = Math.max(1, Math.min(maximumDelay.getValue(), MAX_TRANSACTION_DELAY_MS));
        synchronized (transactionPackets) {
            if (transactionPackets.size() >= MAX_TRANSACTION_PACKETS) {
                transactionPackets.clear();
            }
            transactionPackets.put(rise, Integer.valueOf(delay));
            transactionPackets.put(fall, Integer.valueOf(delay));
        }
        // These two ordered movement packets establish a complete rise/fall
        // transaction before the original attack reaches the transport.
        minecraft.getNetHandler().addToSendQueue(rise);
        minecraft.getNetHandler().addToSendQueue(fall);
        status = "Packet critical";
    }

    @Override
    public int getOutboundPacketDelay(Packet<?> packet) {
        synchronized (transactionPackets) {
            Integer delay = transactionPackets.get(packet);
            return delay == null ? 0 : delay.intValue();
        }
    }

    @Override
    public int getOutboundPacketDelayPriority(Packet<?> packet) {
        synchronized (transactionPackets) {
            return transactionPackets.remove(packet) == null ? 0 : 500;
        }
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (mode.getValue() != Mode.TIMER || !CombatModuleSupport.inGame(minecraft)
                || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            restoreTimer();
            airborneRollPassed = false;
            airborneRollSet = false;
            return;
        }
        if (minecraft.thePlayer.onGround) {
            restoreTimer();
            airborneRollSet = false;
            status = "Ready";
            return;
        }
        if (!airborneRollSet) {
            airborneRollSet = true;
            airborneRollPassed = canActivate(minecraft) && rollChance();
        }
        if (airborneRollPassed && minecraft.thePlayer.motionY > 0.0D) {
            applyTimer(minecraft);
            status = "Timer " + timerSpeed.getValue() + "%";
        } else {
            restoreTimer();
            status = airborneRollPassed ? "Falling" : "Skipped";
        }
    }

    @Override
    protected void onDisable() {
        clearState();
    }

    @Override
    public void onSessionReset() {
        clearState();
    }

    @Override
    public void onInputContextLost() {
        restoreTimer();
        airborneRollPassed = false;
        airborneRollSet = false;
        status = "Paused";
    }

    @Override
    public String getHudInfo() {
        return mode.getValue() + " " + status;
    }

    private boolean canActivate(Minecraft minecraft) {
        return CombatModuleSupport.inGame(minecraft)
            && minecraft.currentScreen == null
            && minecraft.inGameHasFocus
            && (!holdingWeapon.isEnabled() || CombatModuleSupport.holdingWeapon(minecraft))
            && (!mousePressed.isEnabled() || CombatModuleSupport.attackButtonDown(minecraft))
            && !minecraft.thePlayer.isInWater()
            && !minecraft.thePlayer.isOnLadder()
            && !minecraft.thePlayer.isRiding();
    }

    private boolean rollChance() {
        int value = chance.getValue();
        return value >= 100 || (value > 0 && random.nextInt(100) < value);
    }

    private void applyTimer(Minecraft minecraft) {
        if (minecraft.timer == null) {
            return;
        }
        if (!timerOwned) {
            previousTimerSpeed = minecraft.timer.timerSpeed;
            final Timer ownedTimer = minecraft.timer;
            final float restoreSpeed = previousTimerSpeed;
            timerLease = getScope().acquire(ResourceArbiter.Resource.CLIENT_TIMER, 300, 2, new Runnable() {
                @Override
                public void run() {
                    ownedTimer.timerSpeed = restoreSpeed;
                }
            });
            if (timerLease == null) {
                status = "Timer suppressed";
                return;
            }
            timerOwned = true;
        } else if (timerLease == null || !timerLease.renew(2)) {
            timerOwned = false;
            timerLease = null;
            previousTimerSpeed = 1.0F;
            status = "Timer suppressed";
            return;
        }
        minecraft.timer.timerSpeed = timerSpeed.getValue() / 100.0F;
    }

    private void restoreTimer() {
        if (!timerOwned) {
            return;
        }
        ResourceArbiter.Lease lease = timerLease;
        timerLease = null;
        if (lease != null && lease.isValid()) {
            lease.close();
        }
        timerOwned = false;
        previousTimerSpeed = 1.0F;
    }

    private void clearState() {
        restoreTimer();
        synchronized (transactionPackets) {
            transactionPackets.clear();
        }
        airborneRollPassed = false;
        airborneRollSet = false;
        lastPacketCriticalTick = Integer.MIN_VALUE;
        status = "Ready";
    }

    public enum Mode {
        PACKET("Packet"),
        TIMER("Timer");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
