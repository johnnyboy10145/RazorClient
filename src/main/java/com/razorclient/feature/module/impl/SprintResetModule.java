package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import com.razorclient.runtime.ResourceArbiter;
import org.lwjgl.input.Keyboard;

public final class SprintResetModule extends Module {
    private static final long PENDING_TIMEOUT_NANOS = 1_000_000_000L;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.W_TAP);
    private final NumberSetting delayAfterAttack = new NumberSetting("Delay After Attack", 0, 1000, 5, 225);
    private final NumberSetting stopDuration = new NumberSetting("Stop Duration", 5, 250, 5, 50);
    private final BooleanSetting randomize = new BooleanSetting("Randomize", true);
    private final BooleanSetting waitForDamage = new BooleanSetting("Wait For Damage", false);
    private final BooleanSetting holdingWeapon = new BooleanSetting("Holding Weapon", true);

    private final Random random = getScope().getRandom();
    private volatile int pendingTargetId = -1;
    private volatile long pendingAttackNanos;
    private long resetAtNanos;
    private long releaseAtNanos;
    private EntityPlayerSP resetPlayer;
    private Mode resetMode;
    private boolean wasSprinting;
    private boolean resetting;
    private String status = "Ready";
    private ResourceArbiter.Lease inputLease;

    public SprintResetModule() {
        super("Sprint Reset", "Restarts sprint after landing an attack.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(mode);
        addSetting(delayAfterAttack);
        addSetting(stopDuration);
        addSetting(randomize);
        addSetting(waitForDamage);
        addSetting(holdingWeapon);
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if (!CombatModuleSupport.isAttackPacket(packet)) {
            return;
        }
        pendingTargetId = ((C02PacketUseEntity) packet).entityId;
        pendingAttackNanos = System.nanoTime();
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!CombatModuleSupport.inGame(minecraft) || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            clearState(minecraft);
            return;
        }

        long now = System.nanoTime();
        if (resetting) {
            if (now >= releaseAtNanos) {
                finishReset(minecraft);
            }
            return;
        }
        if (resetAtNanos != 0L) {
            if (now >= resetAtNanos) {
                startReset(minecraft, now);
            }
            return;
        }
        if (pendingAttackNanos == 0L) {
            status = "Ready";
            return;
        }
        if (now - pendingAttackNanos > PENDING_TIMEOUT_NANOS) {
            clearPending();
            status = "Expired";
            return;
        }

        Entity target = minecraft.theWorld.getEntityByID(pendingTargetId);
        if (!CombatModuleSupport.validEnemyPlayer(minecraft, target)
                || (holdingWeapon.isEnabled() && !CombatModuleSupport.holdingWeapon(minecraft))) {
            clearPending();
            status = "Condition blocked";
            return;
        }
        if (waitForDamage.isEnabled() && minecraft.thePlayer.hurtTime <= 0) {
            status = "Waiting for damage";
            return;
        }

        resetAtNanos = now + randomizedMillis(delayAfterAttack.getValue()) * 1_000_000L;
        clearPending();
        status = "Scheduled";
    }

    @Override
    protected void onDisable() {
        clearState(Minecraft.getMinecraft());
    }

    @Override
    public void onSessionReset() {
        clearState(Minecraft.getMinecraft());
    }

    @Override
    public void onInputContextLost() {
        clearState(Minecraft.getMinecraft());
    }

    @Override
    public String getHudInfo() {
        return mode.getValue() + " " + status;
    }

    private void startReset(Minecraft minecraft, long now) {
        resetAtNanos = 0L;
        resetPlayer = minecraft.thePlayer;
        resetMode = mode.getValue();
        wasSprinting = resetPlayer.isSprinting();
        ResourceArbiter.Resource resource = resetMode == Mode.SNEAK
            ? ResourceArbiter.Resource.SNEAK_INPUT : ResourceArbiter.Resource.SPRINT_INPUT;
        final EntityPlayerSP player = resetPlayer;
        final Mode activeMode = resetMode;
        final boolean restoreSprint = wasSprinting;
        int leaseTicks = Math.max(2, (stopDuration.getValue() + 49) / 50 + 2);
        inputLease = getScope().acquire(resource, 300, leaseTicks, new Runnable() {
            @Override
            public void run() {
                restoreInput(player, activeMode, restoreSprint);
            }
        });
        if (inputLease == null) {
            resetPlayer = null;
            resetMode = null;
            wasSprinting = false;
            status = "Suppressed";
            return;
        }
        resetting = true;
        releaseAtNanos = now + randomizedMillis(stopDuration.getValue()) * 1_000_000L;
        switch (resetMode) {
            case W_TAP:
                KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindForward.getKeyCode(), false);
                break;
            case SNEAK:
                KeyBinding.setKeyBindState(minecraft.gameSettings.keyBindSneak.getKeyCode(), true);
                break;
            case NO_STOP:
                resetPlayer.setSprinting(false);
                break;
            default:
                break;
        }
        status = "Resetting";
    }

    private void finishReset(Minecraft minecraft) {
        ResourceArbiter.Lease lease = inputLease;
        inputLease = null;
        if (lease != null && lease.isValid()) {
            lease.close();
        }
        resetPlayer = null;
        resetMode = null;
        releaseAtNanos = 0L;
        wasSprinting = false;
        resetting = false;
        status = "Complete";
    }

    private void restoreInput(EntityPlayerSP player, Mode activeMode, boolean restoreSprint) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (player == null || minecraft == null || minecraft.thePlayer != player) {
            return;
        }
        if (activeMode == Mode.W_TAP) {
            int key = minecraft.gameSettings.keyBindForward.getKeyCode();
            KeyBinding.setKeyBindState(key, CombatModuleSupport.physicalKeyDown(key));
        } else if (activeMode == Mode.SNEAK) {
            int key = minecraft.gameSettings.keyBindSneak.getKeyCode();
            KeyBinding.setKeyBindState(key, CombatModuleSupport.physicalKeyDown(key));
        } else if (restoreSprint && player.movementInput != null && player.movementInput.moveForward > 0.0F) {
            player.setSprinting(true);
        }
    }

    private void clearState(Minecraft minecraft) {
        if (resetting) {
            finishReset(minecraft);
        }
        clearPending();
        resetAtNanos = 0L;
        releaseAtNanos = 0L;
        resetPlayer = null;
        resetMode = null;
        inputLease = null;
        resetting = false;
        status = "Ready";
    }

    private void clearPending() {
        pendingTargetId = -1;
        pendingAttackNanos = 0L;
    }

    private long randomizedMillis(int value) {
        if (!randomize.isEnabled() || value <= 1) {
            return value;
        }
        int spread = Math.max(1, value / 10);
        return Math.max(0, value + random.nextInt(spread * 2 + 1) - spread);
    }

    public enum Mode {
        W_TAP("WTap"),
        SNEAK("Sneak"),
        NO_STOP("NoStop");

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
