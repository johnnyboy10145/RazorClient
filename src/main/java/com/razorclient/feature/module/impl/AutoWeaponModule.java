package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import com.razorclient.runtime.ResourceArbiter;
import org.lwjgl.input.Keyboard;

public final class AutoWeaponModule extends Module {
    private final NumberSetting activationTime = new NumberSetting("Activation Time", 0, 1000, 25, 0);
    private final BooleanSetting requireLeftClick = new BooleanSetting("Require Left Click", true);
    private final BooleanSetting returnToSlot = new BooleanSetting("Return To Slot", true);

    private EntityPlayerSP selectingPlayer;
    private int targetEntityId = -1;
    private int savedSlot = -1;
    private long targetSinceNanos;
    private boolean selecting;
    private ResourceArbiter.Lease slotLease;

    public AutoWeaponModule() {
        super("Auto Weapon", "Selects the strongest hotbar weapon while aiming at an enemy.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(activationTime);
        addSetting(requireLeftClick);
        addSetting(returnToSlot);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityLivingBase target = CombatModuleSupport.crosshairLivingTarget(minecraft);
        if (!canSelect(minecraft, target)) {
            stopSelecting(minecraft);
            return;
        }

        int entityId = target.getEntityId();
        long now = System.nanoTime();
        if (entityId != targetEntityId) {
            stopSelecting(minecraft);
            targetEntityId = entityId;
            targetSinceNanos = now;
        }
        if (now - targetSinceNanos < activationTime.getValue() * 1_000_000L) {
            return;
        }

        int bestSlot = findBestSlot(minecraft, target);
        if (bestSlot < 0) {
            stopSelecting(minecraft);
            return;
        }
        if (!selecting) {
            if (bestSlot == minecraft.thePlayer.inventory.currentItem) {
                return;
            }
            final EntityPlayerSP player = minecraft.thePlayer;
            final int originalSlot = player.inventory.currentItem;
            slotLease = getScope().acquire(ResourceArbiter.Resource.HOTBAR_SLOT, 300, 2, new Runnable() {
                @Override
                public void run() {
                    restoreSlot(player, originalSlot);
                }
            });
            if (slotLease == null) {
                return;
            }
            selecting = true;
            selectingPlayer = player;
            savedSlot = originalSlot;
        } else if (slotLease == null || !slotLease.renew(2)) {
            clearSelectionState();
            return;
        }
        minecraft.thePlayer.inventory.currentItem = bestSlot;
        if (minecraft.playerController != null) {
            minecraft.playerController.syncCurrentPlayItem();
        }
    }

    @Override
    protected void onDisable() {
        stopSelecting(Minecraft.getMinecraft());
    }

    @Override
    public void onSessionReset() {
        stopSelecting(Minecraft.getMinecraft());
    }

    @Override
    public void onInputContextLost() {
        stopSelecting(Minecraft.getMinecraft());
    }

    @Override
    public String getHudInfo() {
        return selecting ? "Active" : targetEntityId >= 0 ? "Waiting" : "Ready";
    }

    private boolean canSelect(Minecraft minecraft, EntityLivingBase target) {
        return CombatModuleSupport.inGame(minecraft)
            && minecraft.currentScreen == null
            && minecraft.inGameHasFocus
            && CombatModuleSupport.validEnemyPlayer(minecraft, target)
            && (!requireLeftClick.isEnabled() || CombatModuleSupport.attackButtonDown(minecraft));
    }

    private int findBestSlot(Minecraft minecraft, EntityLivingBase target) {
        int bestSlot = -1;
        double bestDamage = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = minecraft.thePlayer.inventory.getStackInSlot(slot);
            double damage = CombatModuleSupport.weaponDamage(stack, target);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private void stopSelecting(Minecraft minecraft) {
        ResourceArbiter.Lease lease = slotLease;
        slotLease = null;
        if (lease != null && lease.isValid()) lease.close();
        clearSelectionState();
    }

    private void clearSelectionState() {
        selectingPlayer = null;
        targetEntityId = -1;
        savedSlot = -1;
        targetSinceNanos = 0L;
        selecting = false;
    }

    private void restoreSlot(EntityPlayerSP player, int slot) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!returnToSlot.isEnabled() || player == null || slot < 0 || slot >= 9
                || minecraft == null || minecraft.thePlayer != player) {
            return;
        }
        player.inventory.currentItem = slot;
        if (minecraft.playerController != null) {
            minecraft.playerController.syncCurrentPlayItem();
        }
    }
}
