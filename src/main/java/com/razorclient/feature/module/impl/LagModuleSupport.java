package com.razorclient.feature.module.impl;

import com.razorclient.combat.CombatTargetService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

final class LagModuleSupport {
    private LagModuleSupport() {
    }

    static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    static boolean inGame(Minecraft minecraft) {
        return minecraft != null
            && minecraft.thePlayer != null
            && minecraft.theWorld != null;
    }

    static boolean activeInGame(Minecraft minecraft) {
        return inGame(minecraft)
            && minecraft.currentScreen == null
            && minecraft.inGameHasFocus;
    }

    static boolean holdingWeapon(Minecraft minecraft) {
        if (!inGame(minecraft) || minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }

        Item item = minecraft.thePlayer.getHeldItem().getItem();
        if (item instanceof ItemSword || item == Items.stick) {
            return true;
        }

        String name = item.getUnlocalizedName();
        return name != null && name.contains("axe");
    }

    static boolean isMovementPacket(Packet<?> packet) {
        return packet instanceof C03PacketPlayer;
    }

    static boolean isAttackPacket(Packet<?> packet) {
        if (!(packet instanceof C02PacketUseEntity)) {
            return false;
        }
        return ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK;
    }

    static boolean isBlockInteractPacket(Packet<?> packet) {
        return packet instanceof C08PacketPlayerBlockPlacement;
    }

    static boolean isBlockDigPacket(Packet<?> packet) {
        return packet instanceof C07PacketPlayerDigging;
    }

    static boolean isKeepAliveOrTransactionPacket(Packet<?> packet) {
        return packet instanceof C00PacketKeepAlive || packet instanceof C0FPacketConfirmTransaction;
    }

    static boolean isVelocityPacket(Packet<?> packet) {
        return packet instanceof S12PacketEntityVelocity || packet instanceof S27PacketExplosion;
    }

    static boolean isLocalVelocityPacket(Packet<?> packet, EntityPlayerSP player) {
        return packet instanceof S27PacketExplosion || isLocalVelocity(packet, player);
    }

    static boolean isDamageStatus(Packet<?> packet) {
        if (!(packet instanceof S19PacketEntityStatus)) {
            return false;
        }
        return ((S19PacketEntityStatus) packet).getOpCode() == 2;
    }

    static boolean isEntityPositionPacket(Packet<?> packet) {
        return packet instanceof S14PacketEntity || packet instanceof S18PacketEntityTeleport;
    }

    static boolean isLocalVelocity(Packet<?> packet, EntityPlayerSP player) {
        if (player == null || !(packet instanceof S12PacketEntityVelocity)) {
            return false;
        }
        return ((S12PacketEntityVelocity) packet).getEntityID() == player.getEntityId();
    }

    static int getPacketEntityId(Packet<?> packet) {
        if (packet instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) packet).getEntityID();
        }
        if (packet instanceof S14PacketEntity) {
            return ((S14PacketEntity) packet).entityId;
        }
        if (packet instanceof S18PacketEntityTeleport) {
            return ((S18PacketEntityTeleport) packet).entityId;
        }
        return -1;
    }

    static ServerPosition entityServerPosition(Entity entity) {
        if (entity == null) {
            return null;
        }
        return new ServerPosition(
            entity.getEntityId(),
            entity.serverPosX / 32.0D,
            entity.serverPosY / 32.0D,
            entity.serverPosZ / 32.0D
        );
    }

    static ServerPosition decodeServerPosition(Packet<?> packet, ServerPosition previous) {
        int entityId = getPacketEntityId(packet);
        if (entityId < 0) {
            return null;
        }
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleport = (S18PacketEntityTeleport) packet;
            return new ServerPosition(
                entityId,
                teleport.posX / 32.0D,
                teleport.posY / 32.0D,
                teleport.posZ / 32.0D
            );
        }
        if (!(packet instanceof S14PacketEntity)
            || previous == null
            || previous.entityId != entityId) {
            return null;
        }
        S14PacketEntity movement = (S14PacketEntity) packet;
        return new ServerPosition(
            entityId,
            previous.x + movement.posX / 32.0D,
            previous.y + movement.posY / 32.0D,
            previous.z + movement.posZ / 32.0D
        );
    }

    static EntityPlayer closestCombatTarget(Minecraft minecraft, double maxDistance) {
        if (!inGame(minecraft)) {
            return null;
        }

        EntityPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EntityLivingBase candidate : CombatTargetService.candidates(minecraft)) {
            if (!(candidate instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) candidate;
            if (player == minecraft.thePlayer
                || player.isDead
                || player.getHealth() <= 0.0F
                || AntiBotModule.shouldIgnore(player)) {
                continue;
            }

            double distance = minecraft.thePlayer.getDistanceToEntity(player);
            if (distance <= maxDistance && distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    static EntityPlayer crosshairTarget(Minecraft minecraft, double maxDistance, float fov) {
        if (!inGame(minecraft)) {
            return null;
        }

        EntityPlayer best = null;
        double bestAngle = Double.MAX_VALUE;
        for (EntityLivingBase candidate : CombatTargetService.candidates(minecraft)) {
            if (!(candidate instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) candidate;
            if (player == minecraft.thePlayer
                || player.isDead
                || player.getHealth() <= 0.0F
                || AntiBotModule.shouldIgnore(player)
                || minecraft.thePlayer.getDistanceToEntity(player) > maxDistance) {
                continue;
            }

            float yaw = yawTo(player);
            float delta = Math.abs(MathHelper.wrapAngleTo180_float(yaw - minecraft.thePlayer.rotationYaw));
            if (delta <= fov * 0.5F && delta < bestAngle) {
                best = player;
                bestAngle = delta;
            }
        }
        return best;
    }

    static boolean targetInFov(Minecraft minecraft, Entity entity, float fov) {
        if (!inGame(minecraft) || entity == null || fov >= 360.0F) {
            return true;
        }
        float yaw = yawTo(entity);
        return Math.abs(MathHelper.wrapAngleTo180_float(yaw - minecraft.thePlayer.rotationYaw)) <= fov * 0.5F;
    }

    static boolean mouseDown() {
        return Mouse.isButtonDown(0);
    }

    static boolean movingForward(Minecraft minecraft) {
        return inGame(minecraft) && minecraft.thePlayer.moveForward > 0.0F;
    }

    static boolean moving(Minecraft minecraft) {
        return inGame(minecraft)
            && (minecraft.thePlayer.moveForward != 0.0F || minecraft.thePlayer.moveStrafing != 0.0F);
    }

    static boolean sprintResetPacket(Packet<?> packet) {
        return packet instanceof C0BPacketEntityAction
            && ((C0BPacketEntityAction) packet).getAction() == C0BPacketEntityAction.Action.STOP_SPRINTING;
    }

    static boolean splashPotionUse(Packet<?> packet) {
        if (!isBlockInteractPacket(packet)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!inGame(minecraft) || minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }
        Item item = minecraft.thePlayer.getHeldItem().getItem();
        String name = item == null ? "" : item.getUnlocalizedName();
        return name != null && name.contains("potion");
    }

    static boolean scaleLocalVelocity(Packet<?> packet, int horizontalPercent, int verticalPercent) {
        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            velocity.motionX = Math.round(velocity.motionX * horizontalPercent / 100.0F);
            velocity.motionY = Math.round(velocity.motionY * verticalPercent / 100.0F);
            velocity.motionZ = Math.round(velocity.motionZ * horizontalPercent / 100.0F);
            return true;
        } else if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            explosion.field_149152_f *= horizontalPercent / 100.0F;
            explosion.field_149153_g *= verticalPercent / 100.0F;
            explosion.field_149159_h *= horizontalPercent / 100.0F;
            return true;
        }
        return false;
    }

    static void jumpReset(Minecraft minecraft) {
        if (activeInGame(minecraft) && minecraft.thePlayer.onGround) {
            minecraft.thePlayer.jump();
        }
    }

    static void drawEntityBox(EntityLivingBase entity, RenderWorldLastEvent event, float red, float green, float blue) {
        if (entity == null) {
            return;
        }

        drawEntityBoxAt(entity, entity.posX, entity.posY, entity.posZ, event, red, green, blue);
    }

    static void drawEntityBoxAt(
        EntityLivingBase entity,
        double x,
        double y,
        double z,
        RenderWorldLastEvent event,
        float red,
        float green,
        float blue
    ) {
        if (entity == null
            || !Double.isFinite(x)
            || !Double.isFinite(y)
            || !Double.isFinite(z)) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        double renderX = minecraft.getRenderManager().viewerPosX;
        double renderY = minecraft.getRenderManager().viewerPosY;
        double renderZ = minecraft.getRenderManager().viewerPosZ;
        AxisAlignedBB box = entity.getEntityBoundingBox()
            .offset(x - entity.posX - renderX, y - entity.posY - renderY, z - entity.posZ - renderZ);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GL11.glLineWidth(1.5F);
            GlStateManager.color(red, green, blue, 0.85F);
            drawOutlinedBox(box);
        } finally {
            GL11.glLineWidth(1.0F);
            GlStateManager.disableLighting();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private static float yawTo(Entity entity) {
        Minecraft minecraft = Minecraft.getMinecraft();
        double dx = entity.posX - minecraft.thePlayer.posX;
        double dz = entity.posZ - minecraft.thePlayer.posZ;
        return (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
    }

    private static void drawOutlinedBox(AxisAlignedBB box) {
        GL11.glBegin(GL11.GL_LINES);
        vertex(box.minX, box.minY, box.minZ); vertex(box.maxX, box.minY, box.minZ);
        vertex(box.maxX, box.minY, box.minZ); vertex(box.maxX, box.minY, box.maxZ);
        vertex(box.maxX, box.minY, box.maxZ); vertex(box.minX, box.minY, box.maxZ);
        vertex(box.minX, box.minY, box.maxZ); vertex(box.minX, box.minY, box.minZ);

        vertex(box.minX, box.maxY, box.minZ); vertex(box.maxX, box.maxY, box.minZ);
        vertex(box.maxX, box.maxY, box.minZ); vertex(box.maxX, box.maxY, box.maxZ);
        vertex(box.maxX, box.maxY, box.maxZ); vertex(box.minX, box.maxY, box.maxZ);
        vertex(box.minX, box.maxY, box.maxZ); vertex(box.minX, box.maxY, box.minZ);

        vertex(box.minX, box.minY, box.minZ); vertex(box.minX, box.maxY, box.minZ);
        vertex(box.maxX, box.minY, box.minZ); vertex(box.maxX, box.maxY, box.minZ);
        vertex(box.maxX, box.minY, box.maxZ); vertex(box.maxX, box.maxY, box.maxZ);
        vertex(box.minX, box.minY, box.maxZ); vertex(box.minX, box.maxY, box.maxZ);
        GL11.glEnd();
    }

    private static void vertex(double x, double y, double z) {
        GL11.glVertex3d(x, y, z);
    }

    static final class ServerPosition {
        final int entityId;
        final double x;
        final double y;
        final double z;

        ServerPosition(int entityId, double x, double y, double z) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
