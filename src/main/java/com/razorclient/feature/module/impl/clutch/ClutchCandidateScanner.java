package com.razorclient.feature.module.impl.clutch;

import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/** Bounded candidate scanner. Mutable cursors avoid allocating positions for rejected cells. */
public final class ClutchCandidateScanner {
    private static final EnumFacing[] HORIZONTAL = {
        EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };
    private static final EnumFacing[] FACE_PRIORITY = {
        EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH,
        EnumFacing.EAST, EnumFacing.WEST, EnumFacing.UP
    };
    private static final EnumFacing[] ALL_FACES = EnumFacing.values();

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborCursor = new BlockPos.MutableBlockPos();

    public ClutchCandidate findEmergency(World world, EntityPlayerSP player, int scanRange,
            int blocksPlaced, double reach, boolean sidewaysOnly, int fov, boolean multipoint) {
        if (world == null || player == null) return null;
        int blockX = MathHelper.floor_double(player.posX);
        int feetY = MathHelper.floor_double(player.posY);
        int blockZ = MathHelper.floor_double(player.posZ);
        int targetY = MathHelper.floor_double(player.posY + Math.min(player.motionY, 0.0D)) - 1;
        AxisAlignedBB trajectory = player.getEntityBoundingBox().addCoord(
            player.motionX, Math.min(player.motionY, 0.0D), player.motionZ);
        double reachSquared = reach * reach;
        int boundedRange = Math.max(1, Math.min(8, scanRange));
        int minDy = blocksPlaced > 0 ? -4 : -2;
        ClutchCandidate best = null;

        for (int dy = 0; dy >= minDy; dy--) {
            for (int dx = -boundedRange; dx <= boundedRange; dx++) {
                for (int dz = -boundedRange; dz <= boundedRange; dz++) {
                    int airX = blockX + dx;
                    int airY = feetY + dy;
                    int airZ = blockZ + dz;
                    if (!isAir(world, airX, airY, airZ)) continue;
                    if (trajectory.maxX > airX && trajectory.minX < airX + 1.0D
                            && trajectory.maxY > airY && trajectory.minY < airY + 1.0D
                            && trajectory.maxZ > airZ && trajectory.minZ < airZ + 1.0D) continue;

                    for (EnumFacing direction : HORIZONTAL) {
                        int neighborX = airX + direction.getFrontOffsetX();
                        int neighborY = airY + direction.getFrontOffsetY();
                        int neighborZ = airZ + direction.getFrontOffsetZ();
                        Block neighborBlock = blockAt(world, neighborX, neighborY, neighborZ);
                        if (!isAttachable(neighborBlock)) continue;
                        EnumFacing face = direction.getOpposite();
                        if (!isAllowedFace(face, sidewaysOnly)) continue;

                        ClutchCandidate candidate = createCandidate(player, airX, airY, airZ,
                            neighborX, neighborY, neighborZ, face, 0.0D, multipoint);
                        if (!isReachable(player, candidate, reachSquared)
                                || !isWithinFov(player, candidate, fov)) continue;

                        double yDeviation = Math.abs(airY - targetY);
                        double offsetX = airX + 0.5D - player.posX;
                        double offsetZ = airZ + 0.5D - player.posZ;
                        double horizontalDistance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
                        double score = yDeviation * 20.0D + horizontalDistance
                            + rotationDistance(player, candidate) * 0.03D
                            + (neighborBlock.isFullCube() ? 0.0D : 2.0D)
                            + (dx == 0 && dz == 0 ? 6.0D : 0.0D);
                        if (best == null || score < best.getScore()) {
                            best = new ClutchCandidate(candidate.getTargetPos(), candidate.getNeighbor(), face,
                                candidate.getHitX(), candidate.getHitY(), candidate.getHitZ(), score);
                        }
                    }
                }
            }
        }
        return best;
    }

    public ClutchCandidate findNearestEdge(World world, EntityPlayerSP player, int scanRange,
            double reach, boolean sidewaysOnly, int fov, boolean multipoint) {
        if (world == null || player == null) return null;
        int playerX = MathHelper.floor_double(player.posX);
        int playerY = MathHelper.floor_double(player.posY);
        int playerZ = MathHelper.floor_double(player.posZ);
        int boundedRange = Math.max(1, Math.min(8, scanRange));
        double reachSquared = reach * reach;
        ClutchCandidate best = null;

        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -boundedRange; dx <= boundedRange; dx++) {
                for (int dz = -boundedRange; dz <= boundedRange; dz++) {
                    int solidX = playerX + dx;
                    int solidY = playerY + dy;
                    int solidZ = playerZ + dz;
                    if (!isAttachable(blockAt(world, solidX, solidY, solidZ))) continue;

                    for (EnumFacing face : ALL_FACES) {
                        if (!isAllowedFace(face, sidewaysOnly)) continue;
                        int airX = solidX + face.getFrontOffsetX();
                        int airY = solidY + face.getFrontOffsetY();
                        int airZ = solidZ + face.getFrontOffsetZ();
                        if (!isAir(world, airX, airY, airZ)) continue;
                        ClutchCandidate candidate = createCandidate(player, airX, airY, airZ,
                            solidX, solidY, solidZ, face, 0.0D, multipoint);
                        if (!isReachable(player, candidate, reachSquared)
                                || !isWithinFov(player, candidate, fov)) continue;
                        double offsetX = airX + 0.5D - player.posX;
                        double offsetZ = airZ + 0.5D - player.posZ;
                        double score = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ)
                            + Math.abs(airY - (playerY - 1)) * 3.0D;
                        if (best == null || score < best.getScore()) {
                            best = new ClutchCandidate(candidate.getTargetPos(), candidate.getNeighbor(), face,
                                candidate.getHitX(), candidate.getHitY(), candidate.getHitZ(), score);
                        }
                    }
                }
            }
        }
        return best;
    }

    public ClutchCandidate findPlacementInfo(World world, EntityPlayerSP player, BlockPos target,
            boolean sidewaysOnly, boolean multipoint) {
        if (world == null || player == null || target == null
                || !isAir(world, target.getX(), target.getY(), target.getZ())) return null;
        for (EnumFacing direction : FACE_PRIORITY) {
            int nx = target.getX() + direction.getFrontOffsetX();
            int ny = target.getY() + direction.getFrontOffsetY();
            int nz = target.getZ() + direction.getFrontOffsetZ();
            if (!isAttachable(blockAt(world, nx, ny, nz))) continue;
            EnumFacing face = direction.getOpposite();
            if (!isAllowedFace(face, sidewaysOnly)) continue;
            return createCandidate(player, target.getX(), target.getY(), target.getZ(),
                nx, ny, nz, face, 0.0D, multipoint);
        }
        return null;
    }

    public ClutchCandidate makePlacementAgainst(World world, EntityPlayerSP player, BlockPos target,
            BlockPos neighbor, boolean sidewaysOnly, boolean multipoint) {
        if (target == null || neighbor == null
                || !isAttachable(blockAt(world, neighbor.getX(), neighbor.getY(), neighbor.getZ()))) return null;
        int dx = target.getX() - neighbor.getX();
        int dy = target.getY() - neighbor.getY();
        int dz = target.getZ() - neighbor.getZ();
        EnumFacing face = dx == 1 ? EnumFacing.EAST : dx == -1 ? EnumFacing.WEST
            : dz == 1 ? EnumFacing.SOUTH : dz == -1 ? EnumFacing.NORTH
            : dy == 1 ? EnumFacing.UP : dy == -1 ? EnumFacing.DOWN : null;
        if (face == null || !isAllowedFace(face, sidewaysOnly)) return null;
        return createCandidate(player, target.getX(), target.getY(), target.getZ(),
            neighbor.getX(), neighbor.getY(), neighbor.getZ(), face, 0.0D, multipoint);
    }

    public boolean isValid(World world, EntityPlayerSP player, ClutchCandidate candidate,
            double reach, boolean sidewaysOnly, int fov) {
        return candidate != null && world != null && player != null
            && isAir(world, candidate.getTargetPos().getX(), candidate.getTargetPos().getY(), candidate.getTargetPos().getZ())
            && isAttachable(blockAt(world, candidate.getNeighbor().getX(), candidate.getNeighbor().getY(), candidate.getNeighbor().getZ()))
            && isAllowedFace(candidate.getFace(), sidewaysOnly)
            && isReachable(player, candidate, reach * reach)
            && isWithinFov(player, candidate, fov);
    }

    public ClutchCandidate randomizeAim(ClutchCandidate candidate, Random random, int amount) {
        if (candidate == null || random == null || amount <= 0) return candidate;
        EnumFacing face = candidate.getFace();
        BlockPos neighbor = candidate.getNeighbor();
        double spread = 0.12D * Math.min(100, amount) / 100.0D;
        double x = candidate.getHitX();
        double y = candidate.getHitY();
        double z = candidate.getHitZ();
        if (face.getFrontOffsetX() == 0) x += (random.nextDouble() * 2.0D - 1.0D) * spread;
        if (face.getFrontOffsetY() == 0) y += (random.nextDouble() * 2.0D - 1.0D) * spread;
        if (face.getFrontOffsetZ() == 0) z += (random.nextDouble() * 2.0D - 1.0D) * spread;
        return candidate.withHitPoint(
            MathHelper.clamp_double(x, neighbor.getX() + 0.02D, neighbor.getX() + 0.98D),
            MathHelper.clamp_double(y, neighbor.getY() + 0.02D, neighbor.getY() + 0.98D),
            MathHelper.clamp_double(z, neighbor.getZ() + 0.02D, neighbor.getZ() + 0.98D));
    }

    public boolean isAir(World world, BlockPos pos) {
        return pos != null && isAir(world, pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean isAir(World world, int x, int y, int z) {
        cursor.set(x, y, z);
        return world.getBlockState(cursor).getBlock().getMaterial() == Material.air;
    }

    private Block blockAt(World world, int x, int y, int z) {
        neighborCursor.set(x, y, z);
        return world.getBlockState(neighborCursor).getBlock();
    }

    private static boolean isAttachable(Block block) {
        return block != null && block.getMaterial() != Material.air && !(block instanceof BlockLiquid);
    }

    private static boolean isAllowedFace(EnumFacing face, boolean sidewaysOnly) {
        return !sidewaysOnly || (face != EnumFacing.UP && face != EnumFacing.DOWN);
    }

    private static ClutchCandidate createCandidate(EntityPlayerSP player, int tx, int ty, int tz,
            int nx, int ny, int nz, EnumFacing face, double score, boolean multipoint) {
        double hitX = nx + 0.5D + face.getFrontOffsetX() * 0.45D;
        double hitY = ny + 0.5D + face.getFrontOffsetY() * 0.45D;
        double hitZ = nz + 0.5D + face.getFrontOffsetZ() * 0.45D;
        if (multipoint) {
            double eyeY = player.posY + player.getEyeHeight();
            if (face.getFrontOffsetX() == 0) hitX = MathHelper.clamp_double(player.posX, nx + 0.08D, nx + 0.92D);
            if (face.getFrontOffsetY() == 0) hitY = MathHelper.clamp_double(eyeY, ny + 0.08D, ny + 0.92D);
            if (face.getFrontOffsetZ() == 0) hitZ = MathHelper.clamp_double(player.posZ, nz + 0.08D, nz + 0.92D);
        }
        return new ClutchCandidate(new BlockPos(tx, ty, tz), new BlockPos(nx, ny, nz), face,
            hitX, hitY, hitZ, score);
    }

    private static boolean isReachable(EntityPlayerSP player, ClutchCandidate candidate, double reachSquared) {
        double dx = candidate.getHitX() - player.posX;
        double dy = candidate.getHitY() - (player.posY + player.getEyeHeight());
        double dz = candidate.getHitZ() - player.posZ;
        return dx * dx + dy * dy + dz * dz <= reachSquared;
    }

    private static boolean isWithinFov(EntityPlayerSP player, ClutchCandidate candidate, int fov) {
        if (fov >= 180) return true;
        float yaw = candidate.yawFrom(player);
        float pitch = candidate.pitchFrom(player);
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(yaw - player.rotationYaw));
        float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(pitch - player.rotationPitch));
        return MathHelper.sqrt_float(yawDiff * yawDiff + pitchDiff * pitchDiff) <= fov * 0.5F;
    }

    private static double rotationDistance(EntityPlayerSP player, ClutchCandidate candidate) {
        float yaw = Math.abs(MathHelper.wrapAngleTo180_float(candidate.yawFrom(player) - player.rotationYaw));
        float pitch = Math.abs(MathHelper.wrapAngleTo180_float(candidate.pitchFrom(player) - player.rotationPitch));
        return Math.sqrt(yaw * yaw + pitch * pitch);
    }
}
