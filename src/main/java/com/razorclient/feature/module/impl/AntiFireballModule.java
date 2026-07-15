package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.combat.CombatActionCoordinator;
import com.razorclient.combat.KillAuraRotationUtils;
import com.razorclient.event.ClientRotationEvent;
import com.razorclient.event.PrePlayerInputEvent;
import com.razorclient.event.PrePlayerInteractEvent;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.runtime.EntitySnapshotService.ProjectileSnapshot;
import com.razorclient.runtime.EntitySnapshotService.SnapshotFrame;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

public final class AntiFireballModule extends Module {
    private final NumberSetting fov = new NumberSetting("FOV", 30, 360, 4, 360);
    private final DecimalSetting range = new DecimalSetting("Range", 3.0D, 15.0D, 0.5D, 8.0D);
    private final DecimalSetting targetCps = new DecimalSetting("Target CPS", 1.0D, 20.0D, 0.5D, 12.0D);
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 1, 30, 1, 15);
    private final BooleanSetting onGround = new BooleanSetting("On Ground", false);
    private final BooleanSetting sneakWhileActive = new BooleanSetting("Sneak While Active", false);

    private final Random random = getScope().getRandom();
    private final java.lang.reflect.Field pointedEntityField;

    private EntityFireball fireball;
    private long nextClickTime;
    private boolean forgeRegistered;

    public AntiFireballModule() {
        super("AntiFireball", "Automatically aims at and hits nearby fireballs.", Category.PLAYER, Keyboard.KEY_NONE);
        addSetting(fov);
        addSetting(range);
        addSetting(targetCps);
        addSetting(rotationSpeed);
        addSetting(onGround);
        addSetting(sneakWhileActive);
        pointedEntityField = findRendererField("field_78528_u", "pointedEntity");
    }

    @Override
    protected void onEnable() {
        nextClickTime = 0L;
        fireball = null;
        registerForge();
    }

    @Override
    protected void onDisable() {
        unregisterForge();
        nextClickTime = 0L;
        fireball = null;
        ClientRotationHelper.get().clearRequestedRotations("AntiFireball");
    }

    @Override
    public void onSessionReset() {
        nextClickTime = 0L;
        fireball = null;
        ClientRotationHelper.get().clearRequestedRotations("AntiFireball");
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (!canProcess(minecraft)) {
            fireball = null;
            nextClickTime = 0L;
            return;
        }

        fireball = findFireball(minecraft);
        if (shouldCancelMovement(minecraft)) {
            minecraft.thePlayer.setSprinting(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!shouldAim(minecraft)) {
            return;
        }

        float baseYaw = event.yaw != null ? event.yaw.floatValue() : resolveBaseYaw(minecraft);
        float basePitch = event.pitch != null ? event.pitch.floatValue() : resolveBasePitch(minecraft);
        float[] targetRotations = computeAimRotations(baseYaw, basePitch);
        if (targetRotations == null) {
            return;
        }

        float[] smooth = KillAuraRotationUtils.smoothRotation(
            baseYaw,
            basePitch,
            targetRotations[0],
            targetRotations[1],
            rotationSpeed.getValue(),
            0.0F
        );
        if (ClientRotationHelper.get().requestRotations("AntiFireball", 90, smooth[0], smooth[1])) {
            event.yaw = Float.valueOf(smooth[0]);
            event.pitch = Float.valueOf(smooth[1]);
        }
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!shouldCancelMovement(minecraft)) {
            return;
        }

        event.setForward(0.0F);
        event.setStrafe(0.0F);
        event.setJump(false);
        if (sneakWhileActive.isEnabled() && !minecraft.thePlayer.isRiding() && !minecraft.thePlayer.capabilities.isFlying) {
            event.setSneak(true);
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!shouldAim(minecraft)) {
            nextClickTime = 0L;
            return;
        }

        long now = System.nanoTime();
        if (nextClickTime == 0L) {
            nextClickTime = now;
        }

        if (nextClickTime > now) return;
        if (!CombatActionCoordinator.tryAcquire("AntiFireball")) return;
        if (minecraft.playerController == null || fireball == null || fireball.isDead) return;
        minecraft.playerController.attackEntity(minecraft.thePlayer, fireball);
        minecraft.thePlayer.swingItem();
        nextClickTime = now + (nextDelay() * 1000000L);
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldAim(Minecraft.getMinecraft())) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewEntity = minecraft.getRenderViewEntity();
        if (viewEntity == null) {
            return;
        }

        Vec3 eyes = viewEntity.getPositionEyes(partialTicks);
        Vec3 look = viewEntity.getLook(partialTicks);
        double reach = minecraft.playerController == null ? 3.0D : minecraft.playerController.getBlockReachDistance();
        Vec3 rayEnd = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        float border = fireball.getCollisionBorderSize();
        AxisAlignedBB box = fireball.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition intercept = box.calculateIntercept(eyes, rayEnd);
        boolean inside = box.isVecInside(eyes);
        if (!inside && intercept == null) {
            return;
        }

        Vec3 hitVec = inside ? (intercept == null ? eyes : intercept.hitVec) : intercept.hitVec;
        MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(eyes, hitVec, false, false, true);
        if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }

        minecraft.objectMouseOver = new MovingObjectPosition(fireball, hitVec);
        minecraft.pointedEntity = fireball;
        if (pointedEntityField != null) {
            try {
                pointedEntityField.set(minecraft.entityRenderer, fireball);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    @Override
    public String getHudInfo() {
        return fireball == null ? "Ready" : "Target";
    }

    private boolean canProcess(Minecraft minecraft) {
        return minecraft != null
            && minecraft.thePlayer != null
            && minecraft.theWorld != null
            && !minecraft.thePlayer.isDead
            && minecraft.currentScreen == null;
    }

    private boolean shouldAim(Minecraft minecraft) {
        if (!canProcess(minecraft) || fireball == null || fireball.isDead) {
            return false;
        }
        return !onGround.isEnabled() || minecraft.thePlayer.onGround;
    }

    private boolean shouldCancelMovement(Minecraft minecraft) {
        return shouldAim(minecraft);
    }

    private EntityFireball findFireball(Minecraft minecraft) {
        RazorClient client = RazorClient.getInstance();
        if (client == null) {
            return null;
        }

        double rangeSq = range.getValue() * range.getValue();
        float fovValue = (float) fov.getValue();
        EntityFireball best = null;
        double bestDistance = Double.MAX_VALUE;
        SnapshotFrame frame = client.getModuleManager().getEntitySnapshots().current();
        for (int index = 0; index < frame.getProjectileCount(); index++) {
            ProjectileSnapshot snapshot = frame.getProjectile(index);
            if (!snapshot.isFireball() || snapshot.isDead()) {
                continue;
            }

            Entity entity = snapshot.getEntity();
            if (!(entity instanceof EntityFireball)) {
                continue;
            }
            EntityFireball candidate = (EntityFireball) entity;
            if (candidate.isDead || candidate.worldObj != minecraft.theWorld) {
                continue;
            }

            double distanceSq = snapshot.getDistanceSquared();
            if (distanceSq > rangeSq) {
                continue;
            }
            float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(
                snapshot.getYawToCenter() - minecraft.thePlayer.rotationYaw
            ));
            if (fovValue != 360.0F && yawDifference > fovValue * 0.5F) {
                continue;
            }

            if (distanceSq < bestDistance) {
                bestDistance = distanceSq;
                best = candidate;
            }
        }

        return best;
    }

    private float[] computeAimRotations(float baseYaw, float basePitch) {
        if (fireball == null) {
            return null;
        }

        float border = fireball.getCollisionBorderSize();
        AxisAlignedBB fireballBox = fireball.getEntityBoundingBox().expand(border, border, border);

        double topY = fireballBox.maxY;
        double centerX = (fireballBox.minX + fireballBox.maxX) * 0.5D;
        double centerZ = (fireballBox.minZ + fireballBox.maxZ) * 0.5D;
        return KillAuraRotationUtils.getRotationsToPoint(centerX, topY, centerZ, baseYaw, basePitch);
    }

    private long nextDelay() {
        int cps = Math.max(1, (int) targetCps.getValue());
        int baseDelay = 1000 / cps;
        int variation = random.nextInt(Math.max(1, baseDelay / 3 + 1)) - baseDelay / 6;
        return Math.max(33, baseDelay + variation);
    }

    private float resolveBaseYaw(Minecraft minecraft) {
        return Float.isNaN(KillAuraRotationUtils.serverRotations[0]) ? minecraft.thePlayer.rotationYaw : KillAuraRotationUtils.serverRotations[0];
    }

    private float resolveBasePitch(Minecraft minecraft) {
        return Float.isNaN(KillAuraRotationUtils.serverRotations[1]) ? minecraft.thePlayer.rotationPitch : KillAuraRotationUtils.serverRotations[1];
    }

    private void registerForge() {
        if (forgeRegistered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(this);
        forgeRegistered = true;
    }

    private void unregisterForge() {
        if (!forgeRegistered) {
            return;
        }
        MinecraftForge.EVENT_BUS.unregister(this);
        forgeRegistered = false;
    }

    private static java.lang.reflect.Field findRendererField(String... names) {
        try {
            java.lang.reflect.Field field = ReflectionHelper.findField(EntityRenderer.class, names);
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }
}
