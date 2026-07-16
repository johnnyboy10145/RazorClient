package com.razorclient.runtime;

import com.mojang.authlib.GameProfile;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/** Captures one generation-owned immutable scalar entity frame per real client tick. */
public final class EntitySnapshotService {
    private static final int MAX_RECORDS = 1280;
    private final CaptureBuffer capture = new CaptureBuffer();
    private final Set<UUID> tabPlayers = new HashSet<UUID>();
    private volatile EntityFrame entityFrame = EntityFrame.EMPTY;

    public void refresh(TickContext context, Minecraft minecraft) {
        capture.reset();
        if (context == null || minecraft == null || minecraft.theWorld == null
                || minecraft.thePlayer == null) {
            entityFrame = new EntityFrame(context == null ? 0L : context.getSequence(),
                context == null ? 0L : context.getSessionGeneration(), new EntityRecord[0]);
            return;
        }

        collectTabPlayers(minecraft, tabPlayers);
        Vec3 eyes = minecraft.thePlayer.getPositionEyes(1.0F);
        for (Object value : minecraft.theWorld.loadedEntityList) {
            if (!(value instanceof Entity) || value == minecraft.thePlayer) continue;
            Entity entity = (Entity) value;
            EntityRecord record = capture(minecraft, entity, eyes, tabPlayers);
            if (record != null) capture.add(record);
            if (capture.size() >= MAX_RECORDS) break;
        }
        entityFrame = new EntityFrame(context.getSequence(), context.getSessionGeneration(), capture.toOwnedArray());
    }

    public EntityFrame entityFrame() { return entityFrame; }

    public void clear() {
        capture.reset();
        tabPlayers.clear();
        entityFrame = EntityFrame.EMPTY;
    }

    private static EntityRecord capture(Minecraft minecraft, Entity entity, Vec3 eyes,
            Set<UUID> tabPlayers) {
        AxisAlignedBB bounds = entity.getEntityBoundingBox();
        if (bounds == null) return null;
        if (entity instanceof EntityLivingBase) {
            return captureLiving(minecraft, (EntityLivingBase) entity, eyes, tabPlayers, bounds);
        }
        if (entity instanceof EntityFireball || entity instanceof IProjectile) {
            return captureNonLiving(entity, entity instanceof EntityFireball
                ? EntityRecord.Kind.FIREBALL : EntityRecord.Kind.PROJECTILE, eyes, bounds, "", "");
        }
        if (entity instanceof EntityItem) {
            EntityItem item = (EntityItem) entity;
            ItemStack stack = item.getEntityItem();
            String name = stack == null ? "" : safeDisplayName(stack);
            return captureNonLiving(entity, EntityRecord.Kind.DROPPED_ITEM, eyes, bounds, name, name);
        }
        return null;
    }

    private static EntityRecord captureLiving(Minecraft minecraft, EntityLivingBase entity,
            Vec3 eyes, Set<UUID> tabPlayers, AxisAlignedBB bounds) {
        boolean player = entity instanceof EntityPlayer;
        EntityPlayer playerEntity = player ? (EntityPlayer) entity : null;
        ItemStack held = entity.getHeldItem();
        Team team = entity.getTeam();
        return create(entity, player ? EntityRecord.Kind.PLAYER : EntityRecord.Kind.LIVING,
            entity.getName(), team == null ? "" : team.getRegisteredName(),
            held == null ? "" : safeDisplayName(held),
            player && playerEntity.isSpectator(),
            !player || tabPlayers.isEmpty() || tabPlayers.contains(playerEntity.getUniqueID()),
            player && minecraft.thePlayer.isOnSameTeam(entity), entity.isInvisible(),
            minecraft.thePlayer.canEntityBeSeen(entity),
            entity.isDead || entity.deathTime != 0 || entity.getHealth() <= 0.0F,
            eyes, bounds, entity.getHealth(), entity.getMaxHealth(),
            player ? playerEntity.getTotalArmorValue() : 0);
    }

    private static EntityRecord captureNonLiving(Entity entity, EntityRecord.Kind kind,
            Vec3 eyes, AxisAlignedBB bounds, String name, String heldName) {
        return create(entity, kind, name, "", heldName, false, true, false,
            entity.isInvisible(), true, entity.isDead, eyes, bounds, 0.0F, 0.0F, 0);
    }

    private static EntityRecord create(Entity entity, EntityRecord.Kind kind, String name,
            String teamName, String heldName, boolean spectator, boolean inTabList,
            boolean teammate, boolean invisible, boolean visible, boolean dead, Vec3 eyes,
            AxisAlignedBB bounds, float health, float maxHealth, int armor) {
        double nearestX = clamp(eyes.xCoord, bounds.minX, bounds.maxX);
        double nearestY = clamp(eyes.yCoord, bounds.minY, bounds.maxY);
        double nearestZ = clamp(eyes.zCoord, bounds.minZ, bounds.maxZ);
        double dx = nearestX - eyes.xCoord;
        double dy = nearestY - eyes.yCoord;
        double dz = nearestZ - eyes.zCoord;
        double centerX = (bounds.minX + bounds.maxX) * 0.5D - eyes.xCoord;
        double centerY = (bounds.minY + bounds.maxY) * 0.5D - eyes.yCoord;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D - eyes.zCoord;
        double horizontal = Math.sqrt(centerX * centerX + centerZ * centerZ);
        float yaw = (float) (Math.toDegrees(Math.atan2(centerZ, centerX)) - 90.0D);
        float pitch = MathHelper.clamp_float(
            (float) -Math.toDegrees(Math.atan2(centerY, horizontal)), -90.0F, 90.0F);
        return new EntityRecord(entity.getEntityId(), kind, name, teamName, heldName,
            spectator, inTabList, teammate, invisible, visible, dead,
            entity.lastTickPosX, entity.lastTickPosY, entity.lastTickPosZ,
            entity.posX, entity.posY, entity.posZ, entity.motionX, entity.motionY, entity.motionZ,
            bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ,
            Math.sqrt(dx * dx + dy * dy + dz * dz), yaw, pitch, health, maxHealth, armor,
            entity.getCollisionBorderSize());
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

    private static String safeDisplayName(ItemStack stack) {
        try {
            String value = stack.getDisplayName();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class CaptureBuffer {
        private EntityRecord[] records = new EntityRecord[64];
        private int size;

        private void add(EntityRecord record) {
            if (size >= MAX_RECORDS) return;
            if (size == records.length) records = Arrays.copyOf(records,
                Math.min(MAX_RECORDS, records.length << 1));
            records[size++] = record;
        }

        private int size() { return size; }

        private EntityRecord[] toOwnedArray() {
            return size == 0 ? new EntityRecord[0] : Arrays.copyOf(records, size);
        }

        private void reset() {
            Arrays.fill(records, 0, size, null);
            size = 0;
        }
    }
}
