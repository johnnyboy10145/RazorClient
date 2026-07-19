package com.razorclient.feature.module.impl.clutch;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Tracks one controller/fallback attempt without retaining a player or world reference. */
public final class ClutchConfirmationTracker {
    public enum Result {
        NONE,
        PENDING,
        CONFIRMED,
        FAILED,
        INVALID_SESSION
    }

    private ClutchCandidate candidate;
    private Object worldIdentity;
    private int playerEntityId = -1;
    private int slot = -1;
    private int stackCount;
    private int ticksRemaining;
    private boolean bridgeAllowed;

    public void start(ClutchCandidate candidate, Object worldIdentity, int playerEntityId,
            int slot, int stackCount, boolean bridgeAllowed, int confirmationTicks) {
        this.candidate = candidate;
        this.worldIdentity = worldIdentity;
        this.playerEntityId = playerEntityId;
        this.slot = slot;
        this.stackCount = stackCount;
        this.bridgeAllowed = bridgeAllowed;
        this.ticksRemaining = Math.max(1, confirmationTicks);
    }

    public Result poll(World world, EntityPlayerSP player) {
        if (candidate == null) return Result.NONE;
        if (worldIdentity != world || player == null || playerEntityId != player.getEntityId()) {
            return Result.INVALID_SESSION;
        }

        ItemStack current = slot < 0 || slot > 8 ? null : player.inventory.getStackInSlot(slot);
        int currentCount = current == null ? 0 : current.stackSize;
        IBlockState targetState = world.getBlockState(candidate.getTargetPos());
        Block targetBlock = targetState.getBlock();
        boolean worldConfirmed = targetBlock.getMaterial() != Material.air
            && !targetBlock.isReplaceable(world, candidate.getTargetPos());
        if (worldConfirmed || currentCount < stackCount) return Result.CONFIRMED;
        ticksRemaining--;
        return ticksRemaining <= 0 ? Result.FAILED : Result.PENDING;
    }

    public void clear() {
        candidate = null;
        worldIdentity = null;
        playerEntityId = -1;
        slot = -1;
        stackCount = 0;
        ticksRemaining = 0;
        bridgeAllowed = false;
    }

    public ClutchCandidate getCandidate() { return candidate; }
    public boolean isBridgeAllowed() { return bridgeAllowed; }
    public boolean isActive() { return candidate != null; }
}
