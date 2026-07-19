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

    public DangerPrediction predict(World world, EntityPlayerSP player, int predictionTicks,
            int minimumHeight) {
        if (world == null || player == null) {
            return DangerPrediction.safe();
        }

        int ticks = Math.max(1, predictionTicks);
        double x = player.posX;
        double y = player.posY;
        double z = player.posZ;
        double motionX = player.motionX;
        double motionY = player.motionY;
        double motionZ = player.motionZ;
        AxisAlignedBB original = player.getEntityBoundingBox();
        boolean supportLost = !hasCollisionSupport(world, player, original);
        if (supportLost && motionY >= -0.02D) return DangerPrediction.safe();

        for (int tick = 0; tick < ticks; tick++) {
            x += motionX;
            if (supportLost) y += motionY;
            z += motionZ;
            motionX *= 0.91D;
            motionZ *= 0.91D;
            AxisAlignedBB projected = original.offset(
                x - player.posX, y - player.posY, z - player.posZ);
            boolean supported = hasCollisionSupport(world, player, projected);
            if (!supportLost) {
                if (!supported) supportLost = true;
            } else if (supported) {
                return DangerPrediction.safe();
            }
            if (supportLost) motionY = (motionY - 0.08D) * 0.98D;
        }

        if (!supportLost) return DangerPrediction.safe();

        int required = Math.max(1, minimumHeight);
        int unsupportedDepth = countUnsupportedLayers(world, player,
            original.offset(x - player.posX, y - player.posY, z - player.posZ), required);
        return unsupportedDepth >= required
            ? DangerPrediction.dangerous(x, y, z,
                original.offset(x - player.posX, y - player.posY, z - player.posZ),
                ticks, unsupportedDepth)
            : DangerPrediction.safe();
    }

    public boolean isDangerPredicted(World world, EntityPlayerSP player, int predictionTicks,
            int minimumHeight) {
        return predict(world, player, predictionTicks, minimumHeight).isDangerous();
    }

    public boolean hasCollisionSupport(World world, EntityPlayerSP player, AxisAlignedBB box) {
        if (world == null || player == null || box == null) return false;
        AxisAlignedBB probe = new AxisAlignedBB(
            box.minX + 0.02D, box.minY - 0.13D, box.minZ + 0.02D,
            box.maxX - 0.02D, box.minY + 0.01D, box.maxZ - 0.02D);
        for (AxisAlignedBB collision : world.getCollidingBoundingBoxes(player, probe)) {
            if (collision != null && collision.maxY <= box.minY + 0.02D
                    && collision.maxY >= box.minY - 0.14D) return true;
        }
        return false;
    }

    private int countUnsupportedLayers(World world, EntityPlayerSP player, AxisAlignedBB box,
            int limit) {
        int count = 0;
        for (int layer = 0; layer < Math.max(0, limit); layer++) {
            AxisAlignedBB shifted = box.offset(0.0D, -layer, 0.0D);
            if (hasCollisionSupport(world, player, shifted)) break;
            count++;
        }
        return count;
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
