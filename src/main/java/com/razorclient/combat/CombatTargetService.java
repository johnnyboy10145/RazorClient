package com.razorclient.combat;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.impl.AntiBotModule;
import com.razorclient.feature.module.impl.TeamsModule;
import com.razorclient.runtime.EntitySnapshotService.EntitySnapshot;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

/** Shared, frame-scoped combat target validation for live modules. */
public final class CombatTargetService {
    private static final Map<Integer, Double> DISTANCE_CACHE = new HashMap<Integer, Double>();
    private static final List<EntityLivingBase> CANDIDATE_CACHE = new ArrayList<EntityLivingBase>();
    private static Object cachedWorld;
    private static int cachedTick = Integer.MIN_VALUE;
    private static boolean candidateCacheBuilt;
    private static Object publishedWorld;
    private static int publishedTick = Integer.MIN_VALUE;
    private static int publishedTargetId = -1;
    private static int publishedPriority = Integer.MIN_VALUE;

    private CombatTargetService() {
    }

    public static boolean isValid(Minecraft minecraft, EntityLivingBase entity, boolean players, boolean mobs,
            boolean allowInvisible, boolean requireVisibility, boolean ignoreTeammates, double maxDistance) {
        if (minecraft == null || minecraft.thePlayer == null || minecraft.theWorld == null || entity == null
                || entity == minecraft.thePlayer || entity.isDead || entity.deathTime != 0 || entity.getHealth() <= 0.0F) {
            return false;
        }
        EntitySnapshot snapshot = snapshot(entity);
        if (snapshot != null && snapshot.isDead()) return false;
        if (entity instanceof EntityPlayer) {
            if (!players || AntiBotModule.shouldIgnore((EntityPlayer) entity)) {
                return false;
            }
            if (ignoreTeammates && ((snapshot == null ? minecraft.thePlayer.isOnSameTeam(entity) : snapshot.isTeammate())
                    || TeamsModule.isTeammate((EntityPlayer) entity))) {
                return false;
            }
        } else if (!mobs) {
            return false;
        }
        if (!allowInvisible && (snapshot == null ? entity.isInvisible() : snapshot.isInvisible())) {
            return false;
        }
        if (requireVisibility && !(snapshot == null ? minecraft.thePlayer.canEntityBeSeen(entity) : snapshot.isVisible())) {
            return false;
        }
        return distanceToHitbox(minecraft, entity) <= maxDistance;
    }

    public static double distanceToHitbox(Minecraft minecraft, EntityLivingBase entity) {
        if (minecraft == null || minecraft.thePlayer == null || minecraft.theWorld == null || entity == null) {
            return Double.MAX_VALUE;
        }
        EntitySnapshot snapshot = snapshot(entity);
        if (snapshot != null) return snapshot.getDistanceToHitbox();
        resetCacheIfNeeded(minecraft);
        Integer id = Integer.valueOf(entity.getEntityId());
        Double cached = DISTANCE_CACHE.get(id);
        if (cached != null) {
            return cached.doubleValue();
        }
        Vec3 eye = minecraft.thePlayer.getPositionEyes(1.0F);
        double x = clamp(eye.xCoord, entity.getEntityBoundingBox().minX, entity.getEntityBoundingBox().maxX);
        double y = clamp(eye.yCoord, entity.getEntityBoundingBox().minY, entity.getEntityBoundingBox().maxY);
        double z = clamp(eye.zCoord, entity.getEntityBoundingBox().minZ, entity.getEntityBoundingBox().maxZ);
        double distance = eye.distanceTo(new Vec3(x, y, z));
        DISTANCE_CACHE.put(id, Double.valueOf(distance));
        return distance;
    }

    public static List<EntityLivingBase> candidates(Minecraft minecraft) {
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null) return Collections.emptyList();
        resetCacheIfNeeded(minecraft);
        if (!candidateCacheBuilt) {
            candidateCacheBuilt = true;
            RazorClient client = RazorClient.getInstance();
            if (client != null) {
                for (EntitySnapshot snapshot : client.getModuleManager().getEntitySnapshots().current()) {
                    if (snapshot.getEntity() != null) CANDIDATE_CACHE.add(snapshot.getEntity());
                }
            }
        }
        return Collections.unmodifiableList(CANDIDATE_CACHE);
    }

    public static void clear() {
        DISTANCE_CACHE.clear();
        CANDIDATE_CACHE.clear();
        cachedWorld = null;
        cachedTick = Integer.MIN_VALUE;
        candidateCacheBuilt = false;
        publishedWorld = null;
        publishedTick = Integer.MIN_VALUE;
        publishedTargetId = -1;
        publishedPriority = Integer.MIN_VALUE;
    }

    /** Publishes a read-only, one-tick combat target for visual consumers. */
    public static void publishTarget(Minecraft minecraft, EntityLivingBase target, int priority) {
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null || target == null) return;
        int tick = minecraft.thePlayer.ticksExisted;
        if (publishedWorld != minecraft.theWorld || publishedTick != tick) {
            publishedWorld = minecraft.theWorld;
            publishedTick = tick;
            publishedTargetId = -1;
            publishedPriority = Integer.MIN_VALUE;
        }
        if (priority >= publishedPriority) {
            publishedPriority = priority;
            publishedTargetId = target.getEntityId();
        }
    }

    public static int getPublishedTargetId(Minecraft minecraft) {
        if (minecraft == null || minecraft.thePlayer == null || publishedWorld != minecraft.theWorld
                || publishedTick < minecraft.thePlayer.ticksExisted - 1) return -1;
        return publishedTargetId;
    }

    private static void resetCacheIfNeeded(Minecraft minecraft) {
        if (cachedWorld == minecraft.theWorld && cachedTick == minecraft.thePlayer.ticksExisted) {
            return;
        }
        DISTANCE_CACHE.clear();
        CANDIDATE_CACHE.clear();
        candidateCacheBuilt = false;
        cachedWorld = minecraft.theWorld;
        cachedTick = minecraft.thePlayer.ticksExisted;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static EntitySnapshot snapshot(EntityLivingBase entity) {
        RazorClient client = RazorClient.getInstance();
        if (client == null || entity == null) return null;
        EntitySnapshot snapshot = client.getModuleManager().getEntitySnapshots().find(entity.getEntityId());
        return snapshot != null && snapshot.getEntity() == entity ? snapshot : null;
    }
}
