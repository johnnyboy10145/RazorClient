package com.razorclient.feature.module.impl.clutch;

import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/** Client-thread-only fall predictor with a reusable block cursor. */
public final class ClutchPredictor {
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public boolean isDangerPredicted(World world, EntityPlayerSP player, int predictionTicks,
            int minimumHeight) {
        if (world == null || player == null || player.onGround || player.motionY >= -0.02D) {
            return false;
        }

        int ticks = Math.max(1, predictionTicks);
        double x = player.posX;
        double y = player.posY;
        double z = player.posZ;
        double motionX = player.motionX;
        double motionY = player.motionY;
        double motionZ = player.motionZ;
        AxisAlignedBB original = player.getEntityBoundingBox();

        for (int tick = 0; tick < ticks; tick++) {
            x += motionX;
            y += motionY;
            z += motionZ;
            motionY = (motionY - 0.08D) * 0.98D;
            motionX *= 0.91D;
            motionZ *= 0.91D;
            AxisAlignedBB projected = original.offset(
                x - player.posX, y - player.posY, z - player.posZ);
            if (hasCollisionSupport(world, player, projected)) return false;
        }

        int required = Math.max(1, minimumHeight);
        return countAirBelow(world, x, y, z, required) >= required;
    }

    public boolean hasCollisionSupport(World world, EntityPlayerSP player, AxisAlignedBB box) {
        if (world == null || player == null || box == null) return false;
        AxisAlignedBB probe = box.offset(0.0D, -0.12D, 0.0D).contract(0.02D, 0.0D, 0.02D);
        return !world.getCollidingBoundingBoxes(player, probe).isEmpty();
    }

    public int countAirBelow(World world, EntityPlayerSP player, int limit) {
        if (player == null) return 0;
        return countAirBelow(world, player.posX, player.posY, player.posZ, limit);
    }

    public int countAirBelow(World world, double x, double y, double z, int limit) {
        if (world == null) return 0;
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        int feetY = MathHelper.floor_double(y);
        int count = 0;
        for (int offset = 1; offset <= Math.max(0, limit); offset++) {
            cursor.set(blockX, feetY - offset, blockZ);
            if (world.getBlockState(cursor).getBlock().getMaterial() != Material.air) break;
            count++;
        }
        return count;
    }
}
