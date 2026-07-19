package com.razorclient.feature.module.impl.clutch;

import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Bounded candidate scanner. Mutable cursors avoid allocating positions for rejected cells. */
public final class ClutchCandidateScanner {
    private static final int MAX_RAYTRACE_FINALISTS = 24;
    private static final double[] MULTIPOINT_SAMPLES = { 0.18D, 0.50D, 0.82D };
    private static final EnumFacing[] FACE_PRIORITY = {
        EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH,
        EnumFacing.EAST, EnumFacing.WEST, EnumFacing.UP
    };
    private static final EnumFacing[] ALL_FACES = EnumFacing.values();

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborCursor = new BlockPos.MutableBlockPos();
    private final ClutchCandidate[] finalists = new ClutchCandidate[MAX_RAYTRACE_FINALISTS];
    private final double[] finalistScores = new double[MAX_RAYTRACE_FINALISTS];
    private int finalistCount;

    public ClutchCandidate findEmergency(World world, EntityPlayerSP player,
            DangerPrediction prediction, int scanRange, int blocksPlaced, double reach,
            boolean sidewaysOnly, int fov, boolean multipoint) {
        if (world == null || player == null) return null;
        boolean predicted = prediction != null && prediction.isDangerous();
        double projectedX = predicted ? prediction.getProjectedX() : player.posX + player.motionX;
        double projectedY = predicted ? prediction.getProjectedY() : player.posY + Math.min(player.motionY, 0.0D);
        double projectedZ = predicted ? prediction.getProjectedZ() : player.posZ + player.motionZ;
        int blockX = MathHelper.floor_double(projectedX);
        int feetY = MathHelper.floor_double(player.posY);
        int blockZ = MathHelper.floor_double(projectedZ);
        int targetY = MathHelper.floor_double(projectedY) - 1;
        AxisAlignedBB currentBounds = player.getEntityBoundingBox();
        double reachSquared = reach * reach;
        int boundedRange = Math.max(1, Math.min(8, scanRange));
        int minDy = blocksPlaced > 0 ? -4 : -2;
        clearFinalists();

        for (int dy = -1; dy >= minDy - 1; dy--) {
            int airY = feetY + dy;
            for (int dx = -boundedRange; dx <= boundedRange; dx++) {
                for (int dz = -boundedRange; dz <= boundedRange; dz++) {
                    int airX = blockX + dx;
                    int airZ = blockZ + dz;
                    if (!isAir(world, airX, airY, airZ)) continue;
                    if (intersects(currentBounds, airX, airY, airZ)) continue;

                    for (EnumFacing direction : FACE_PRIORITY) {
                        int neighborX = airX + direction.getFrontOffsetX();
                        int neighborY = airY + direction.getFrontOffsetY();
                        int neighborZ = airZ + direction.getFrontOffsetZ();
                        if (!isAttachable(world, neighborX, neighborY, neighborZ)) continue;
                        EnumFacing face = direction.getOpposite();
                        if (!isAllowedFace(face, sidewaysOnly)) continue;

                        ClutchCandidate candidate = createCandidate(player, airX, airY, airZ,
                            neighborX, neighborY, neighborZ, face, 0.0D, false);
                        if (!isReachable(player, candidate, reachSquared)
                                || !isWithinFov(player, candidate, fov)) continue;

                        double yDeviation = Math.abs(airY - targetY);
                        double offsetX = airX + 0.5D - projectedX;
                        double offsetZ = airZ + 0.5D - projectedZ;
                        double horizontalDistance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
                        double score = yDeviation * 20.0D + horizontalDistance
                            + rotationDistance(player, candidate) * 0.03D
                            + (face == EnumFacing.UP ? 0.0D : 0.35D)
                            + (dx == 0 && dz == 0 ? 0.0D : 0.25D);
                        insertFinalist(new ClutchCandidate(candidate.getTargetPos(), candidate.getNeighbor(), face,
                            candidate.getHitX(), candidate.getHitY(), candidate.getHitZ(), score), score);
                    }
                }
            }
        }
        for (int index = 0; index < finalistCount; index++) {
            ClutchCandidate geometric = finalists[index];
            ClutchCandidate ready = createRayTraceReadyCandidate(world, player,
                geometric.getTargetPos().getX(), geometric.getTargetPos().getY(), geometric.getTargetPos().getZ(),
                geometric.getNeighbor().getX(), geometric.getNeighbor().getY(), geometric.getNeighbor().getZ(),
                geometric.getFace(), multipoint, reachSquared, fov);
            if (ready != null) {
                double score = finalistScores[index];
                clearFinalists();
                return new ClutchCandidate(ready.getTargetPos(), ready.getNeighbor(), ready.getFace(),
                    ready.getHitX(), ready.getHitY(), ready.getHitZ(), score);
            }
        }
        clearFinalists();
        return null;
    }

    public ClutchCandidate findNearestEdge(World world, EntityPlayerSP player, int scanRange,
            double reach, boolean sidewaysOnly, int fov, boolean multipoint) {
        if (world == null || player == null) return null;
        int playerX = MathHelper.floor_double(player.posX);
        int playerY = MathHelper.floor_double(player.posY);
        int playerZ = MathHelper.floor_double(player.posZ);
        int boundedRange = Math.max(1, Math.min(8, scanRange));
        double reachSquared = reach * reach;
        clearFinalists();

        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -boundedRange; dx <= boundedRange; dx++) {
                for (int dz = -boundedRange; dz <= boundedRange; dz++) {
                    int solidX = playerX + dx;
                    int solidY = playerY + dy;
                    int solidZ = playerZ + dz;
                    if (!isAttachable(world, solidX, solidY, solidZ)) continue;

                    for (EnumFacing face : ALL_FACES) {
                        if (!isAllowedFace(face, sidewaysOnly)) continue;
                        int airX = solidX + face.getFrontOffsetX();
                        int airY = solidY + face.getFrontOffsetY();
                        int airZ = solidZ + face.getFrontOffsetZ();
                        if (!isAir(world, airX, airY, airZ)) continue;
                        ClutchCandidate candidate = createCandidate(player, airX, airY, airZ,
                            solidX, solidY, solidZ, face, 0.0D, false);
                        if (!isReachable(player, candidate, reachSquared)
                                || !isWithinFov(player, candidate, fov)) continue;
                        double offsetX = airX + 0.5D - player.posX;
                        double offsetZ = airZ + 0.5D - player.posZ;
                        double score = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ)
                            + Math.abs(airY - (playerY - 1)) * 3.0D;
                        insertFinalist(new ClutchCandidate(candidate.getTargetPos(), candidate.getNeighbor(), face,
                            candidate.getHitX(), candidate.getHitY(), candidate.getHitZ(), score), score);
                    }
                }
            }
        }
        for (int index = 0; index < finalistCount; index++) {
            ClutchCandidate geometric = finalists[index];
            ClutchCandidate ready = createRayTraceReadyCandidate(world, player,
                geometric.getTargetPos().getX(), geometric.getTargetPos().getY(), geometric.getTargetPos().getZ(),
                geometric.getNeighbor().getX(), geometric.getNeighbor().getY(), geometric.getNeighbor().getZ(),
                geometric.getFace(), multipoint, reachSquared, fov);
            if (ready != null) {
                double score = finalistScores[index];
                clearFinalists();
                return new ClutchCandidate(ready.getTargetPos(), ready.getNeighbor(), ready.getFace(),
                    ready.getHitX(), ready.getHitY(), ready.getHitZ(), score);
            }
        }
        clearFinalists();
        return null;
    }

    public ClutchCandidate findPlacementInfo(World world, EntityPlayerSP player, BlockPos target,
            boolean sidewaysOnly, boolean multipoint) {
        if (world == null || player == null || target == null
                || !isAir(world, target.getX(), target.getY(), target.getZ())) return null;
        for (EnumFacing direction : FACE_PRIORITY) {
            int nx = target.getX() + direction.getFrontOffsetX();
            int ny = target.getY() + direction.getFrontOffsetY();
            int nz = target.getZ() + direction.getFrontOffsetZ();
            if (!isAttachable(world, nx, ny, nz)) continue;
            EnumFacing face = direction.getOpposite();
            if (!isAllowedFace(face, sidewaysOnly)) continue;
            ClutchCandidate candidate = createRayTraceReadyCandidate(world, player,
                target.getX(), target.getY(), target.getZ(), nx, ny, nz, face,
                multipoint, defaultReachSquared(player), 180);
            if (candidate != null) return candidate;
        }
        return null;
    }

    public ClutchCandidate makePlacementAgainst(World world, EntityPlayerSP player, BlockPos target,
            BlockPos neighbor, boolean sidewaysOnly, boolean multipoint) {
        if (target == null || neighbor == null
                || !isAir(world, target.getX(), target.getY(), target.getZ())
                || !isAttachable(world, neighbor.getX(), neighbor.getY(), neighbor.getZ())) return null;
        int dx = target.getX() - neighbor.getX();
        int dy = target.getY() - neighbor.getY();
        int dz = target.getZ() - neighbor.getZ();
        EnumFacing face = dx == 1 ? EnumFacing.EAST : dx == -1 ? EnumFacing.WEST
            : dz == 1 ? EnumFacing.SOUTH : dz == -1 ? EnumFacing.NORTH
            : dy == 1 ? EnumFacing.UP : dy == -1 ? EnumFacing.DOWN : null;
        if (face == null || !isAllowedFace(face, sidewaysOnly)) return null;
        return createRayTraceReadyCandidate(world, player, target.getX(), target.getY(), target.getZ(),
            neighbor.getX(), neighbor.getY(), neighbor.getZ(), face, multipoint,
            defaultReachSquared(player), 180);
    }

    public boolean isValid(World world, EntityPlayerSP player, ClutchCandidate candidate,
            double reach, boolean sidewaysOnly, int fov) {
        return candidate != null && world != null && player != null
            && isAir(world, candidate.getTargetPos().getX(), candidate.getTargetPos().getY(), candidate.getTargetPos().getZ())
            && isAttachable(world, candidate.getNeighbor().getX(), candidate.getNeighbor().getY(), candidate.getNeighbor().getZ())
            && isAllowedFace(candidate.getFace(), sidewaysOnly)
            && isReachable(player, candidate, reach * reach)
            && isWithinFov(player, candidate, fov)
            && isRayTraceReady(world, player, candidate);
    }

    public ClutchCandidate randomizeAim(World world, EntityPlayerSP player,
            ClutchCandidate candidate, Random random, int amount) {
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
        ClutchCandidate randomized = candidate.withHitPoint(
            MathHelper.clamp_double(x, neighbor.getX() + 0.02D, neighbor.getX() + 0.98D),
            MathHelper.clamp_double(y, neighbor.getY() + 0.02D, neighbor.getY() + 0.98D),
            MathHelper.clamp_double(z, neighbor.getZ() + 0.02D, neighbor.getZ() + 0.98D));
        return isRayTraceReady(world, player, randomized) ? randomized : candidate;
    }

    public boolean isAir(World world, BlockPos pos) {
        return pos != null && isAir(world, pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean isAir(World world, int x, int y, int z) {
        if (world == null) return false;
        cursor.set(x, y, z);
        IBlockState state = world.getBlockState(cursor);
        Block block = state.getBlock();
        return block.getMaterial() == Material.air || block.isReplaceable(world, cursor);
    }

    private boolean isAttachable(World world, int x, int y, int z) {
        if (world == null) return false;
        neighborCursor.set(x, y, z);
        IBlockState state = world.getBlockState(neighborCursor);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        return block != null && material != Material.air && material.isSolid()
            && !(block instanceof BlockLiquid) && !block.isReplaceable(world, neighborCursor)
            && block.getCollisionBoundingBox(world, neighborCursor, state) != null;
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

    private ClutchCandidate createRayTraceReadyCandidate(World world, EntityPlayerSP player,
            int tx, int ty, int tz, int nx, int ny, int nz, EnumFacing face,
            boolean multipoint, double reachSquared, int fov) {
        ClutchCandidate center = createCandidate(player, tx, ty, tz, nx, ny, nz, face, 0.0D, false);
        ClutchCandidate best = isCandidateReady(world, player, center, reachSquared, fov) ? center : null;
        if (!multipoint) return best;

        BlockPos target = center.getTargetPos();
        BlockPos neighbor = center.getNeighbor();
        for (double first : MULTIPOINT_SAMPLES) {
            for (double second : MULTIPOINT_SAMPLES) {
                double x = neighbor.getX() + 0.5D;
                double y = neighbor.getY() + 0.5D;
                double z = neighbor.getZ() + 0.5D;
                if (face.getFrontOffsetX() != 0) {
                    x = neighbor.getX() + (face == EnumFacing.EAST ? 0.98D : 0.02D);
                    y = neighbor.getY() + first;
                    z = neighbor.getZ() + second;
                } else if (face.getFrontOffsetY() != 0) {
                    y = neighbor.getY() + (face == EnumFacing.UP ? 0.98D : 0.02D);
                    x = neighbor.getX() + first;
                    z = neighbor.getZ() + second;
                } else {
                    z = neighbor.getZ() + (face == EnumFacing.SOUTH ? 0.98D : 0.02D);
                    x = neighbor.getX() + first;
                    y = neighbor.getY() + second;
                }
                ClutchCandidate candidate = new ClutchCandidate(target, neighbor, face, x, y, z, 0.0D);
                if (!isCandidateReady(world, player, candidate, reachSquared, fov)) continue;
                if (best == null || rotationDistance(player, candidate) < rotationDistance(player, best)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean isCandidateReady(World world, EntityPlayerSP player,
            ClutchCandidate candidate, double reachSquared, int fov) {
        return isReachable(player, candidate, reachSquared)
            && isWithinFov(player, candidate, fov)
            && isRayTraceReady(world, player, candidate);
    }

    public static boolean isRayTraceReady(World world, EntityPlayerSP player,
            ClutchCandidate candidate) {
        if (world == null || player == null || candidate == null) return false;
        Vec3 eyes = player.getPositionEyes(1.0F);
        Vec3 hitPoint = new Vec3(candidate.getHitX(), candidate.getHitY(), candidate.getHitZ());
        MovingObjectPosition hit = world.rayTraceBlocks(eyes, hitPoint, false, true, false);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && candidate.getNeighbor().equals(hit.getBlockPos())
            && candidate.getFace() == hit.sideHit;
    }

    private static boolean intersects(AxisAlignedBB bounds, int x, int y, int z) {
        return bounds != null && bounds.maxX > x && bounds.minX < x + 1.0D
            && bounds.maxY > y && bounds.minY < y + 1.0D
            && bounds.maxZ > z && bounds.minZ < z + 1.0D;
    }

    private static double defaultReachSquared(EntityPlayerSP player) {
        double reach = player != null && player.capabilities.isCreativeMode ? 5.0D : 4.5D;
        return reach * reach;
    }

    private void clearFinalists() {
        for (int index = 0; index < finalistCount; index++) finalists[index] = null;
        finalistCount = 0;
    }

    private void insertFinalist(ClutchCandidate candidate, double score) {
        int insertion = finalistCount;
        while (insertion > 0 && finalistScores[insertion - 1] > score) insertion--;
        if (insertion >= MAX_RAYTRACE_FINALISTS) return;
        int newCount = Math.min(MAX_RAYTRACE_FINALISTS, finalistCount + 1);
        for (int index = newCount - 1; index > insertion; index--) {
            finalists[index] = finalists[index - 1];
            finalistScores[index] = finalistScores[index - 1];
        }
        finalists[insertion] = candidate;
        finalistScores[insertion] = score;
        finalistCount = newCount;
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
