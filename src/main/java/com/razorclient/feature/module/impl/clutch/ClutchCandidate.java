package com.razorclient.feature.module.impl.clutch;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;

/** Immutable placement candidate. Its hit point is fixed for the whole attempt. */
public final class ClutchCandidate {
    private final BlockPos targetPos;
    private final BlockPos neighbor;
    private final EnumFacing face;
    private final double hitX;
    private final double hitY;
    private final double hitZ;
    private final double score;

    public ClutchCandidate(BlockPos targetPos, BlockPos neighbor, EnumFacing face,
            double hitX, double hitY, double hitZ, double score) {
        this.targetPos = targetPos;
        this.neighbor = neighbor;
        this.face = face;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
        this.score = score;
    }

    public BlockPos getTargetPos() { return targetPos; }
    public BlockPos getNeighbor() { return neighbor; }
    public EnumFacing getFace() { return face; }
    public double getHitX() { return hitX; }
    public double getHitY() { return hitY; }
    public double getHitZ() { return hitZ; }
    public double getScore() { return score; }

    public float yawFrom(EntityPlayerSP player) {
        double deltaX = hitX - player.posX;
        double deltaZ = hitZ - player.posZ;
        return (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
    }

    public float pitchFrom(EntityPlayerSP player) {
        double deltaX = hitX - player.posX;
        double deltaY = hitY - (player.posY + player.getEyeHeight());
        double deltaZ = hitZ - player.posZ;
        double horizontal = Math.max(0.01D, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
        return MathHelper.clamp_float(
            (float) Math.toDegrees(Math.atan2(-deltaY, horizontal)), -90.0F, 90.0F);
    }

    public ClutchCandidate withHitPoint(double x, double y, double z) {
        return new ClutchCandidate(targetPos, neighbor, face, x, y, z, score);
    }
}
