package com.razorclient.runtime;

import com.mojang.authlib.GameProfile;
import java.util.AbstractList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/**
 * Performs one living-entity traversal per real tick. Published snapshots are read-only and
 * valid until the service advances again; two alternating buffers prevent render/tick overlap.
 */
public final class EntitySnapshotService {
    private static final int MAX_ENTITIES = 512;
    private static final int MAX_PROJECTILES = 256;
    private static final int MAX_DROPPED_ITEMS = 512;
    private final SnapshotBuffer[] buffers = { new SnapshotBuffer(), new SnapshotBuffer() };
    private int writeIndex;
    private volatile SnapshotFrame current = SnapshotFrame.EMPTY;

    public void refresh(TickContext context) {
        SnapshotBuffer buffer = buffers[writeIndex ^= 1];
        buffer.reset();
        Minecraft minecraft = context == null ? null : context.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null) {
            current = new SnapshotFrame(context == null ? 0L : context.getSequence(), buffer);
            return;
        }

        Set<UUID> tabPlayers = buffer.tabPlayers;
        collectTabPlayers(minecraft, tabPlayers);
        Vec3 eyes = minecraft.thePlayer.getPositionEyes(1.0F);
        for (Object value : minecraft.theWorld.loadedEntityList) {
            if (value instanceof EntityLivingBase
                && value != minecraft.thePlayer
                && buffer.size < MAX_ENTITIES) {
                EntityLivingBase entity = (EntityLivingBase) value;
                EntitySnapshot snapshot = buffer.next();
                try {
                    snapshot.capture(minecraft, entity, eyes, tabPlayers);
                    buffer.byEntityId.put(Integer.valueOf(snapshot.entityId), snapshot);
                } catch (Throwable ignored) {
                    buffer.discardLiving();
                }
            }

            if (value instanceof Entity
                && isProjectile(value)
                && buffer.projectileSize < MAX_PROJECTILES) {
                ProjectileSnapshot snapshot = buffer.nextProjectile();
                try {
                    snapshot.capture(minecraft, (Entity) value, eyes);
                    buffer.projectilesByEntityId.put(Integer.valueOf(snapshot.entityId), snapshot);
                } catch (Throwable ignored) {
                    buffer.discardProjectile();
                }
            }

            if (value instanceof EntityItem && buffer.droppedItemSize < MAX_DROPPED_ITEMS) {
                buffer.addDroppedItem((EntityItem) value);
            }

            if (buffer.size >= MAX_ENTITIES
                && buffer.projectileSize >= MAX_PROJECTILES
                && buffer.droppedItemSize >= MAX_DROPPED_ITEMS) {
                break;
            }
        }
        current = new SnapshotFrame(context.getSequence(), buffer);
    }

    public SnapshotFrame current() { return current; }

    public EntitySnapshot find(int entityId) {
        return current.find(entityId);
    }

    public void clear() {
        buffers[0].reset();
        buffers[1].reset();
        current = SnapshotFrame.EMPTY;
    }

    private static void collectTabPlayers(Minecraft minecraft, Set<UUID> result) {
        result.clear();
        if (minecraft.getNetHandler() == null) return;
        Collection<NetworkPlayerInfo> players = minecraft.getNetHandler().getPlayerInfoMap();
        if (players == null) return;
        for (NetworkPlayerInfo info : players) {
            GameProfile profile = info == null ? null : info.getGameProfile();
            if (profile != null && profile.getId() != null) result.add(profile.getId());
        }
    }

    private static boolean isProjectile(Object value) {
        return value instanceof EntityFireball || value instanceof IProjectile;
    }

    public static final class SnapshotFrame extends AbstractList<EntitySnapshot> {
        private static final SnapshotFrame EMPTY = new SnapshotFrame(0L, new SnapshotBuffer());
        private final long tickSequence;
        private final SnapshotBuffer buffer;
        private final int size;
        private final int projectileSize;
        private final int droppedItemSize;

        private SnapshotFrame(long tickSequence, SnapshotBuffer buffer) {
            this.tickSequence = tickSequence;
            this.buffer = buffer;
            this.size = buffer.size;
            this.projectileSize = buffer.projectileSize;
            this.droppedItemSize = buffer.droppedItemSize;
        }

        public long getTickSequence() { return tickSequence; }
        @Override public EntitySnapshot get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(String.valueOf(index));
            return buffer.entries[index];
        }
        @Override public int size() { return size; }
        public EntitySnapshot find(int entityId) { return buffer.byEntityId.get(Integer.valueOf(entityId)); }
        public int getProjectileCount() { return projectileSize; }
        public ProjectileSnapshot getProjectile(int index) {
            if (index < 0 || index >= projectileSize) throw new IndexOutOfBoundsException(String.valueOf(index));
            return buffer.projectiles[index];
        }
        public ProjectileSnapshot findProjectile(int entityId) {
            return buffer.projectilesByEntityId.get(Integer.valueOf(entityId));
        }
        public int getDroppedItemCount() { return droppedItemSize; }
        public EntityItem getDroppedItem(int index) {
            if (index < 0 || index >= droppedItemSize) throw new IndexOutOfBoundsException(String.valueOf(index));
            return buffer.droppedItems[index];
        }
    }

    public static final class ProjectileSnapshot {
        private Entity entity;
        private int entityId;
        private boolean fireball;
        private boolean dead;
        private double lastX;
        private double lastY;
        private double lastZ;
        private double x;
        private double y;
        private double z;
        private double motionX;
        private double motionY;
        private double motionZ;
        private double minX;
        private double minY;
        private double minZ;
        private double maxX;
        private double maxY;
        private double maxZ;
        private double distanceSquared;
        private double distanceToHitbox;
        private float yawToCenter;
        private float pitchToCenter;
        private float collisionBorder;

        private void capture(Minecraft minecraft, Entity value, Vec3 eyes) {
            entity = value;
            entityId = value.getEntityId();
            fireball = value instanceof EntityFireball;
            dead = value.isDead;
            lastX = value.lastTickPosX;
            lastY = value.lastTickPosY;
            lastZ = value.lastTickPosZ;
            x = value.posX;
            y = value.posY;
            z = value.posZ;
            motionX = value.motionX;
            motionY = value.motionY;
            motionZ = value.motionZ;
            AxisAlignedBB bounds = value.getEntityBoundingBox();
            minX = bounds.minX;
            minY = bounds.minY;
            minZ = bounds.minZ;
            maxX = bounds.maxX;
            maxY = bounds.maxY;
            maxZ = bounds.maxZ;
            double playerDx = x - minecraft.thePlayer.posX;
            double playerDy = y - minecraft.thePlayer.posY;
            double playerDz = z - minecraft.thePlayer.posZ;
            distanceSquared = playerDx * playerDx + playerDy * playerDy + playerDz * playerDz;
            double nearestX = clamp(eyes.xCoord, minX, maxX);
            double nearestY = clamp(eyes.yCoord, minY, maxY);
            double nearestZ = clamp(eyes.zCoord, minZ, maxZ);
            double dx = nearestX - eyes.xCoord;
            double dy = nearestY - eyes.yCoord;
            double dz = nearestZ - eyes.zCoord;
            distanceToHitbox = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double centerX = (minX + maxX) * 0.5D - eyes.xCoord;
            double centerY = (minY + maxY) * 0.5D - eyes.yCoord;
            double centerZ = (minZ + maxZ) * 0.5D - eyes.zCoord;
            double horizontal = Math.sqrt(centerX * centerX + centerZ * centerZ);
            yawToCenter = (float) (Math.toDegrees(Math.atan2(centerZ, centerX)) - 90.0D);
            pitchToCenter = MathHelper.clamp_float(
                (float) -Math.toDegrees(Math.atan2(centerY, horizontal)), -90.0F, 90.0F
            );
            collisionBorder = value.getCollisionBorderSize();
        }

        public Entity getEntity() { return entity; }
        public int getEntityId() { return entityId; }
        public boolean isFireball() { return fireball; }
        public boolean isDead() { return dead; }
        public double getLastX() { return lastX; }
        public double getLastY() { return lastY; }
        public double getLastZ() { return lastZ; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public double getMotionX() { return motionX; }
        public double getMotionY() { return motionY; }
        public double getMotionZ() { return motionZ; }
        public double getMinX() { return minX; }
        public double getMinY() { return minY; }
        public double getMinZ() { return minZ; }
        public double getMaxX() { return maxX; }
        public double getMaxY() { return maxY; }
        public double getMaxZ() { return maxZ; }
        public double getDistanceSquared() { return distanceSquared; }
        public double getDistanceToHitbox() { return distanceToHitbox; }
        public float getYawToCenter() { return yawToCenter; }
        public float getPitchToCenter() { return pitchToCenter; }
        public float getCollisionBorder() { return collisionBorder; }
        public double interpolateX(float partialTicks) { return lastX + (x - lastX) * partialTicks; }
        public double interpolateY(float partialTicks) { return lastY + (y - lastY) * partialTicks; }
        public double interpolateZ(float partialTicks) { return lastZ + (z - lastZ) * partialTicks; }

        private static double clamp(double value, double minimum, double maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    public static final class EntitySnapshot {
        private EntityLivingBase entity;
        private int entityId;
        private String name;
        private String teamName;
        private String heldItemName;
        private boolean player;
        private boolean spectator;
        private boolean inTabList;
        private boolean teammate;
        private boolean invisible;
        private boolean visible;
        private boolean dead;
        private double lastX;
        private double lastY;
        private double lastZ;
        private double x;
        private double y;
        private double z;
        private double minX;
        private double minY;
        private double minZ;
        private double maxX;
        private double maxY;
        private double maxZ;
        private double distanceToHitbox;
        private float yawToCenter;
        private float pitchToCenter;
        private float health;
        private float maxHealth;
        private int armor;

        private void capture(Minecraft minecraft, EntityLivingBase value, Vec3 eyes, Set<UUID> tabPlayers) {
            entity = value;
            entityId = value.getEntityId();
            name = value.getName();
            player = value instanceof EntityPlayer;
            spectator = player && ((EntityPlayer) value).isSpectator();
            inTabList = !player || tabPlayers.isEmpty() || tabPlayers.contains(((EntityPlayer) value).getUniqueID());
            teammate = player && minecraft.thePlayer.isOnSameTeam(value);
            invisible = value.isInvisible();
            visible = minecraft.thePlayer.canEntityBeSeen(value);
            dead = value.isDead || value.deathTime != 0 || value.getHealth() <= 0.0F;
            lastX = value.lastTickPosX;
            lastY = value.lastTickPosY;
            lastZ = value.lastTickPosZ;
            x = value.posX;
            y = value.posY;
            z = value.posZ;
            AxisAlignedBB bounds = value.getEntityBoundingBox();
            minX = bounds.minX;
            minY = bounds.minY;
            minZ = bounds.minZ;
            maxX = bounds.maxX;
            maxY = bounds.maxY;
            maxZ = bounds.maxZ;
            double nearestX = clamp(eyes.xCoord, minX, maxX);
            double nearestY = clamp(eyes.yCoord, minY, maxY);
            double nearestZ = clamp(eyes.zCoord, minZ, maxZ);
            double dx = nearestX - eyes.xCoord;
            double dy = nearestY - eyes.yCoord;
            double dz = nearestZ - eyes.zCoord;
            distanceToHitbox = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double centerX = (minX + maxX) * 0.5D - eyes.xCoord;
            double centerY = (minY + maxY) * 0.5D - eyes.yCoord;
            double centerZ = (minZ + maxZ) * 0.5D - eyes.zCoord;
            double horizontal = Math.sqrt(centerX * centerX + centerZ * centerZ);
            yawToCenter = (float) (Math.toDegrees(Math.atan2(centerZ, centerX)) - 90.0D);
            pitchToCenter = MathHelper.clamp_float((float) -Math.toDegrees(Math.atan2(centerY, horizontal)), -90.0F, 90.0F);
            health = value.getHealth();
            maxHealth = value.getMaxHealth();
            armor = value.getTotalArmorValue();
            ItemStack held = value.getHeldItem();
            heldItemName = held == null ? "" : held.getDisplayName();
            Team team = value.getTeam();
            teamName = team == null ? "" : team.getRegisteredName();
        }

        public EntityLivingBase getEntity() { return entity; }
        public int getEntityId() { return entityId; }
        public String getName() { return name; }
        public String getTeamName() { return teamName; }
        public String getHeldItemName() { return heldItemName; }
        public boolean isPlayer() { return player; }
        public boolean isSpectator() { return spectator; }
        public boolean isInTabList() { return inTabList; }
        public boolean isTeammate() { return teammate; }
        public boolean isInvisible() { return invisible; }
        public boolean isVisible() { return visible; }
        public boolean isDead() { return dead; }
        public double getLastX() { return lastX; }
        public double getLastY() { return lastY; }
        public double getLastZ() { return lastZ; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public double getMinX() { return minX; }
        public double getMinY() { return minY; }
        public double getMinZ() { return minZ; }
        public double getMaxX() { return maxX; }
        public double getMaxY() { return maxY; }
        public double getMaxZ() { return maxZ; }
        public double getDistanceToHitbox() { return distanceToHitbox; }
        public float getYawToCenter() { return yawToCenter; }
        public float getPitchToCenter() { return pitchToCenter; }
        public float getHealth() { return health; }
        public float getMaxHealth() { return maxHealth; }
        public int getArmor() { return armor; }
        public double interpolateX(float partialTicks) { return lastX + (x - lastX) * partialTicks; }
        public double interpolateY(float partialTicks) { return lastY + (y - lastY) * partialTicks; }
        public double interpolateZ(float partialTicks) { return lastZ + (z - lastZ) * partialTicks; }

        private static double clamp(double value, double minimum, double maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }

    private static final class SnapshotBuffer {
        private EntitySnapshot[] entries = new EntitySnapshot[64];
        private ProjectileSnapshot[] projectiles = new ProjectileSnapshot[32];
        private EntityItem[] droppedItems = new EntityItem[32];
        private final Map<Integer, EntitySnapshot> byEntityId = new HashMap<Integer, EntitySnapshot>();
        private final Map<Integer, ProjectileSnapshot> projectilesByEntityId =
            new HashMap<Integer, ProjectileSnapshot>();
        private final Set<UUID> tabPlayers = new HashSet<UUID>();
        private int size;
        private int projectileSize;
        private int droppedItemSize;

        private EntitySnapshot next() {
            if (size == entries.length) {
                EntitySnapshot[] expanded = new EntitySnapshot[Math.min(MAX_ENTITIES, entries.length * 2)];
                System.arraycopy(entries, 0, expanded, 0, entries.length);
                entries = expanded;
            }
            EntitySnapshot snapshot = entries[size];
            if (snapshot == null) {
                snapshot = new EntitySnapshot();
                entries[size] = snapshot;
            }
            size++;
            return snapshot;
        }

        private ProjectileSnapshot nextProjectile() {
            if (projectileSize == projectiles.length) {
                ProjectileSnapshot[] expanded = new ProjectileSnapshot[
                    Math.min(MAX_PROJECTILES, projectiles.length * 2)
                ];
                System.arraycopy(projectiles, 0, expanded, 0, projectiles.length);
                projectiles = expanded;
            }
            ProjectileSnapshot snapshot = projectiles[projectileSize];
            if (snapshot == null) {
                snapshot = new ProjectileSnapshot();
                projectiles[projectileSize] = snapshot;
            }
            projectileSize++;
            return snapshot;
        }

        private void addDroppedItem(EntityItem item) {
            if (droppedItemSize == droppedItems.length) {
                EntityItem[] expanded = new EntityItem[
                    Math.min(MAX_DROPPED_ITEMS, droppedItems.length * 2)
                ];
                System.arraycopy(droppedItems, 0, expanded, 0, droppedItems.length);
                droppedItems = expanded;
            }
            droppedItems[droppedItemSize++] = item;
        }

        private void discardLiving() {
            if (size > 0) size--;
        }

        private void discardProjectile() {
            if (projectileSize > 0) projectileSize--;
        }

        private void reset() {
            for (int index = 0; index < droppedItemSize; index++) droppedItems[index] = null;
            size = 0;
            projectileSize = 0;
            droppedItemSize = 0;
            byEntityId.clear();
            projectilesByEntityId.clear();
            tabPlayers.clear();
        }
    }
}
