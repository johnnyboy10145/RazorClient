package com.razorclient.combat;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.impl.AntiBotModule;
import com.razorclient.feature.module.impl.TeamsModule;
import com.razorclient.runtime.EntityFrame;
import com.razorclient.runtime.EntityRecord;
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

    private CombatTargetService() {
    }

    public static boolean isValid(Minecraft minecraft, EntityLivingBase entity, boolean players, boolean mobs,
            boolean allowInvisible, boolean requireVisibility, boolean ignoreTeammates, double maxDistance) {
        if (minecraft == null || minecraft.thePlayer == null || minecraft.theWorld == null || entity == null
                || entity == minecraft.thePlayer || entity.isDead || entity.deathTime != 0 || entity.getHealth() <= 0.0F) {
            return false;
        }
        EntityRecord snapshot = snapshot(minecraft, entity);
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
        EntityRecord snapshot = snapshot(minecraft, entity);
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
                EntityFrame frame = client.getModuleManager().getEntityFrame();
                for (int index = 0; index < frame.size(); index++) {
                    EntityRecord snapshot = frame.get(index);
                    if (snapshot == null || (!snapshot.isPlayer()
                            && snapshot.getKind() != EntityRecord.Kind.LIVING)) continue;
                    net.minecraft.entity.Entity entity = minecraft.theWorld.getEntityByID(snapshot.getEntityId());
                    if (entity instanceof EntityLivingBase) CANDIDATE_CACHE.add((EntityLivingBase) entity);
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

    private static EntityRecord snapshot(Minecraft minecraft, EntityLivingBase entity) {
        RazorClient client = RazorClient.getInstance();
        if (client == null || minecraft == null || minecraft.theWorld == null || entity == null
                || minecraft.theWorld.getEntityByID(entity.getEntityId()) != entity) return null;
        return client.getModuleManager().getEntityFrame().find(entity.getEntityId());
    }
}
