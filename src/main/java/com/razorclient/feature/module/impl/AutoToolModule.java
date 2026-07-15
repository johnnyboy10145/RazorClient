package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.runtime.ResourceArbiter;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class AutoToolModule extends Module {
    private final BooleanSetting returnToSlot = new BooleanSetting("Return To Slot", true);
    private int savedSlot = -1;
    private boolean selecting;
    private EntityPlayerSP selectingPlayer;
    private ResourceArbiter.Lease slotLease;

    public AutoToolModule() {
        super("Auto Tool", "Selects the strongest hotbar tool while mining.", Category.PLAYER, Keyboard.KEY_NONE);
        addSetting(returnToSlot);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!canSelect(minecraft)) {
            stopSelecting();
            return;
        }

        BlockPos position = minecraft.objectMouseOver.getBlockPos();
        Block block = minecraft.theWorld.getBlockState(position).getBlock();
        int bestSlot = findBestSlot(minecraft, block);
        if (bestSlot < 0) return;
        if (!selecting) {
            if (bestSlot == minecraft.thePlayer.inventory.currentItem) return;
            final EntityPlayerSP player = minecraft.thePlayer;
            final int originalSlot = player.inventory.currentItem;
            slotLease = getScope().acquire(ResourceArbiter.Resource.HOTBAR_SLOT, 100, 2, new Runnable() {
                @Override public void run() { restoreSlot(player, originalSlot); }
            });
            if (slotLease == null) return;
            savedSlot = originalSlot;
            selectingPlayer = player;
            selecting = true;
        } else if (slotLease == null || !slotLease.renew(2)) {
            clearSelectionState();
            return;
        }
        minecraft.thePlayer.inventory.currentItem = bestSlot;
        if (minecraft.playerController != null) minecraft.playerController.syncCurrentPlayItem();
    }

    @Override
    protected void onDisable() {
        stopSelecting();
    }

    @Override
    public void onSessionReset() {
        stopSelecting();
    }

    @Override
    public void onInputContextLost() {
        stopSelecting();
    }

    @Override
    public String getHudInfo() {
        return selecting ? "Active" : "Ready";
    }

    private boolean canSelect(Minecraft minecraft) {
        return minecraft != null && minecraft.thePlayer != null && minecraft.theWorld != null
            && minecraft.currentScreen == null && minecraft.inGameHasFocus && Mouse.isButtonDown(0)
            && minecraft.objectMouseOver != null
            && minecraft.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && minecraft.objectMouseOver.getBlockPos() != null;
    }

    private int findBestSlot(Minecraft minecraft, Block block) {
        int bestSlot = minecraft.thePlayer.inventory.currentItem;
        float bestStrength = strength(minecraft.thePlayer.inventory.getStackInSlot(bestSlot), block);
        for (int slot = 0; slot < 9; slot++) {
            float candidate = strength(minecraft.thePlayer.inventory.getStackInSlot(slot), block);
            if (candidate > bestStrength) {
                bestStrength = candidate;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private float strength(ItemStack stack, Block block) {
        return stack == null || block == null ? 1.0F : stack.getStrVsBlock(block);
    }

    private void stopSelecting() {
        ResourceArbiter.Lease lease = slotLease;
        slotLease = null;
        if (lease != null && lease.isValid()) lease.close();
        clearSelectionState();
    }

    private void clearSelectionState() {
        savedSlot = -1;
        selecting = false;
        selectingPlayer = null;
    }

    private void restoreSlot(EntityPlayerSP player, int slot) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!returnToSlot.isEnabled() || player == null || slot < 0 || slot >= 9
                || minecraft == null || minecraft.thePlayer != player) return;
        player.inventory.currentItem = slot;
        if (minecraft.playerController != null) minecraft.playerController.syncCurrentPlayItem();
    }
}
