package com.razorclient.feature.module.impl.clutch;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/** Reusable bounded Manhattan recovery path. */
public final class ClutchBridgePlanner {
    private final List<BlockPos> path = new ArrayList<BlockPos>(64);
    private int index;
    private ClutchCandidate startCandidate;

    public boolean refresh(World world, EntityPlayerSP player, ClutchCandidateScanner scanner,
            int scanRange, int maxBlocks, int availableBlocks, double reach,
            boolean sidewaysOnly, int fov, boolean multipoint) {
        clear();
        int limit = Math.max(0, Math.min(64, Math.min(maxBlocks, availableBlocks)));
        if (limit == 0) return false;
        ClutchCandidate edge = scanner.findNearestEdge(world, player, scanRange, reach,
            sidewaysOnly, fov, multipoint);
        if (edge == null) return false;

        double futureX = player.posX + player.motionX;
        double futureZ = player.posZ + player.motionZ;
        int playerX = MathHelper.floor_double(futureX);
        int playerZ = MathHelper.floor_double(futureZ);
        int distance = Math.max(1, Math.abs(edge.getTargetPos().getX() - playerX)
            + Math.abs(edge.getTargetPos().getZ() - playerZ));
        int bridgeY = Math.min(MathHelper.floor_double(predictY(player, distance)) - 1,
            edge.getNeighbor().getY());
        int x = edge.getTargetPos().getX();
        int z = edge.getTargetPos().getZ();
        add(x, bridgeY, z, limit);
        while ((x != playerX || z != playerZ) && path.size() < limit) {
            int dx = playerX - x;
            int dz = playerZ - z;
            if (Math.abs(dx) >= Math.abs(dz)) x += dx > 0 ? 1 : -1;
            else z += dz > 0 ? 1 : -1;
            add(x, bridgeY, z, limit);
        }

        if (path.size() < limit) {
            double fracX = futureX - Math.floor(futureX);
            if (fracX >= 0.7D) add(x + 1, bridgeY, z, limit);
            else if (fracX <= 0.3D) add(x - 1, bridgeY, z, limit);
        }
        BlockPos last = path.get(path.size() - 1);
        if (path.size() < limit) {
            double fracZ = futureZ - Math.floor(futureZ);
            if (fracZ >= 0.7D) add(last.getX(), bridgeY, last.getZ() + 1, limit);
            else if (fracZ <= 0.3D) add(last.getX(), bridgeY, last.getZ() - 1, limit);
        }

        startCandidate = scanner.findPlacementInfo(world, player, path.get(0), sidewaysOnly, multipoint);
        if (startCandidate == null) {
            startCandidate = new ClutchCandidate(path.get(0), edge.getNeighbor(), edge.getFace(),
                edge.getHitX(), edge.getHitY(), edge.getHitZ(), edge.getScore());
        }
        return true;
    }

    public ClutchCandidate next(World world, EntityPlayerSP player, ClutchCandidateScanner scanner,
            boolean sidewaysOnly, boolean multipoint) {
        while (index < path.size() && !scanner.isAir(world, path.get(index))) index++;
        if (index >= path.size()) return null;
        if (index == 0 && startCandidate != null) return startCandidate;
        ClutchCandidate candidate = index > 0
            ? scanner.makePlacementAgainst(world, player, path.get(index), path.get(index - 1),
                sidewaysOnly, multipoint)
            : null;
        return candidate != null ? candidate
            : scanner.findPlacementInfo(world, player, path.get(index), sidewaysOnly, multipoint);
    }

    public void advance(ClutchCandidate candidate) {
        if (candidate != null && index < path.size()
                && candidate.getTargetPos().equals(path.get(index))) {
            index++;
            startCandidate = null;
        }
    }

    public void clear() {
        path.clear();
        index = 0;
        startCandidate = null;
    }

    public boolean isEmpty() { return path.isEmpty(); }
    public boolean isComplete() { return !path.isEmpty() && index >= path.size(); }
    public int size() { return path.size(); }
    public int getIndex() { return index; }
    public String status() {
        return path.isEmpty() ? "Bridge" : "Bridge " + Math.min(index + 1, path.size()) + "/" + path.size();
    }

    private void add(int x, int y, int z, int limit) {
        if (path.size() < limit) path.add(new BlockPos(x, y, z));
    }

    private static double predictY(EntityPlayerSP player, int ticks) {
        double y = player.posY;
        double velocity = player.motionY;
        for (int i = 0; i < ticks; i++) {
            velocity = (velocity - 0.08D) * 0.98D;
            y += velocity;
        }
        return y;
    }
}
