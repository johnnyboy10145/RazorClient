package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.NumberSetting;
import java.util.ArrayList;
import java.util.List;
import com.razorclient.runtime.EntitySnapshotService.EntitySnapshot;
import com.razorclient.runtime.EntitySnapshotService.SnapshotFrame;
import com.razorclient.runtime.FrameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSnowball;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class TrajectoriesModule extends Module {
    private static final double SIMULATION_STEP = 0.1D;
    private static final int MAX_SIMULATION_STEPS = 512;
    private final List<Vec3> pathBuffer = new ArrayList<Vec3>(MAX_SIMULATION_STEPS + 2);
    private final float[] aimingColorBuffer = new float[3];
    private final float[] trajectoryColorBuffer = new float[3];

    private final NumberSetting aimingRed = new NumberSetting("Aiming Red", 0, 255, 5, 85);
    private final NumberSetting aimingGreen = new NumberSetting("Aiming Green", 0, 255, 5, 255);
    private final NumberSetting aimingBlue = new NumberSetting("Aiming Blue", 0, 255, 5, 85);
    private final NumberSetting trajectoryRed = new NumberSetting("Trajectory Red", 0, 255, 5, 255);
    private final NumberSetting trajectoryGreen = new NumberSetting("Trajectory Green", 0, 255, 5, 255);
    private final NumberSetting trajectoryBlue = new NumberSetting("Trajectory Blue", 0, 255, 5, 255);
    private final NumberSetting targetRed = new NumberSetting("Target Red", 0, 255, 5, 255);
    private final NumberSetting targetGreen = new NumberSetting("Target Green", 0, 255, 5, 80);
    private final NumberSetting targetBlue = new NumberSetting("Target Blue", 0, 255, 5, 80);
    private final NumberSetting thickness = new NumberSetting("Thickness", 1, 6, 1, 2);

    public TrajectoriesModule() {
        super("Trajectories", "Predicts projectile flight paths and highlights entity hits.", Category.RENDER, Keyboard.KEY_NONE);
        addSetting(aimingRed);
        addSetting(aimingGreen);
        addSetting(aimingBlue);
        addSetting(trajectoryRed);
        addSetting(trajectoryGreen);
        addSetting(trajectoryBlue);
        addSetting(targetRed);
        addSetting(targetGreen);
        addSetting(targetBlue);
        addSetting(thickness);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null || minecraft.theWorld == null || player.getHeldItem() == null) {
            return;
        }

        ProjectileProperties properties = getProjectileProperties(player);
        if (properties == null) {
            return;
        }

        SimulationResult result = simulatePath(minecraft, player, properties, event.partialTicks);
        if (result.points.size() < 2) {
            return;
        }

        float[] lineColor = result.entityHit == null ? getTrajectoryColor() : getAimingColor();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(thickness.getValue());

            renderPath(minecraft, result.points, lineColor);
        } finally {
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.0F);
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    @Override
    public String getHudInfo() {
        return thickness.getValue() + "px";
    }

    private ProjectileProperties getProjectileProperties(EntityPlayerSP player) {
        Item item = player.getHeldItem().getItem();
        if (item instanceof ItemBow) {
            if (!player.isUsingItem()) {
                return null;
            }

            float power = (72000 - player.getItemInUseCount()) / 20.0F;
            power = (power * power + power * 2.0F) / 3.0F;
            if (power < 0.1F) {
                return null;
            }
            if (power > 1.0F) {
                power = 1.0F;
            }
            return new ProjectileProperties(3.0D * power, 0.05D, 0.99D, 0.16D, 0.0F);
        }

        if (item instanceof ItemFishingRod) {
            return new ProjectileProperties(1.5D, 0.04D, 0.92D, 0.16D, 0.0F);
        }

        if (item instanceof ItemPotion) {
            return new ProjectileProperties(0.5D, 0.05D, 0.95D, 0.16D, -20.0F);
        }

        if (item instanceof ItemSnowball || item instanceof ItemEgg || item instanceof ItemEnderPearl) {
            return new ProjectileProperties(1.5D, 0.03D, 0.99D, 0.16D, 0.0F);
        }

        return null;
    }

    private SimulationResult simulatePath(Minecraft minecraft, EntityPlayerSP player, ProjectileProperties properties, float partialTicks) {
        List<Vec3> points = pathBuffer;
        points.clear();
        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch + properties.pitchOffset);
        Vec3 position = getStartPosition(player, yawRadians, partialTicks, properties);
        Vec3 motion = getInitialMotion(yawRadians, pitchRadians, properties.velocity);

        points.add(position);
        for (int i = 0; i < MAX_SIMULATION_STEPS; i++) {
            Vec3 nextPosition = position.addVector(
                motion.xCoord * SIMULATION_STEP,
                motion.yCoord * SIMULATION_STEP,
                motion.zCoord * SIMULATION_STEP
            );
            MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(position, nextPosition, false, true, false);

            if (blockHit != null) {
                points.add(blockHit.hitVec);
                break;
            }

            position = nextPosition;
            points.add(position);
            motion = new Vec3(
                motion.xCoord * getStepDrag(properties.drag),
                motion.yCoord * getStepDrag(properties.drag) - properties.gravity * SIMULATION_STEP,
                motion.zCoord * getStepDrag(properties.drag)
            );

            if (position.yCoord < 0.0D || position.yCoord > minecraft.theWorld.getActualHeight()) {
                break;
            }
        }

        EntityPathHit entityHit = findEntityHit(minecraft, player, points);
        if (entityHit != null) {
            while (points.size() > entityHit.segmentIndex + 1) {
                points.remove(points.size() - 1);
            }
            points.add(entityHit.hitVec);
            return new SimulationResult(points, entityHit.entity);
        }
        return new SimulationResult(points, null);
    }

    private Vec3 getStartPosition(EntityPlayerSP player, double yawRadians, float partialTicks, ProjectileProperties properties) {
        double interpX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double interpY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks + player.getEyeHeight();
        double interpZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        return new Vec3(
            interpX - Math.cos(yawRadians) * properties.startOffset,
            interpY - 0.1D,
            interpZ - Math.sin(yawRadians) * properties.startOffset
        );
    }

    private Vec3 getInitialMotion(double yawRadians, double pitchRadians, double velocity) {
        double motionX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double motionY = -Math.sin(pitchRadians);
        double motionZ = Math.cos(yawRadians) * Math.cos(pitchRadians);
        double motionLength = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
        if (motionLength == 0.0D) {
            return new Vec3(0.0D, 0.0D, 0.0D);
        }

        return new Vec3(
            motionX / motionLength * velocity,
            motionY / motionLength * velocity,
            motionZ / motionLength * velocity
        );
    }

    private double getStepDrag(double drag) {
        return 1.0D - ((1.0D - drag) * SIMULATION_STEP);
    }

    private EntityPathHit findEntityHit(Minecraft minecraft, EntityPlayerSP player, List<Vec3> points) {
        if (points.size() < 2) return null;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 point : points) {
            minX = Math.min(minX, point.xCoord);
            minY = Math.min(minY, point.yCoord);
            minZ = Math.min(minZ, point.zCoord);
            maxX = Math.max(maxX, point.xCoord);
            maxY = Math.max(maxY, point.yCoord);
            maxZ = Math.max(maxZ, point.zCoord);
        }

        Entity bestEntity = null;
        Vec3 bestHitVec = null;
        int bestSegment = Integer.MAX_VALUE;
        double bestDistance = Double.POSITIVE_INFINITY;
        RazorClient client = RazorClient.getInstance();
        if (client == null) return null;
        SnapshotFrame snapshots = client.getModuleManager().getEntitySnapshots().current();
        for (int snapshotIndex = 0; snapshotIndex < snapshots.size(); snapshotIndex++) {
            EntitySnapshot snapshot = snapshots.get(snapshotIndex);
            Entity entity = snapshot.getEntity();
            if (snapshot.isDead()
                || entity instanceof EntityFishHook
                || entity instanceof EntityArmorStand
                || !entity.canBeCollidedWith()) {
                continue;
            }
            if (snapshot.getMaxX() + 1.0D < minX || snapshot.getMinX() - 1.0D > maxX
                    || snapshot.getMaxY() + 1.0D < minY || snapshot.getMinY() - 1.0D > maxY
                    || snapshot.getMaxZ() + 1.0D < minZ || snapshot.getMinZ() - 1.0D > maxZ) continue;

            float border = Math.max(0.45F, entity.getCollisionBorderSize());
            AxisAlignedBB box = new AxisAlignedBB(snapshot.getMinX(), snapshot.getMinY(), snapshot.getMinZ(),
                snapshot.getMaxX(), snapshot.getMaxY(), snapshot.getMaxZ()).expand(border, border, border);
            for (int segment = 0; segment < points.size() - 1; segment++) {
                if (segment > bestSegment) break;
                Vec3 start = points.get(segment);
                Vec3 end = points.get(segment + 1);
                Vec3 candidate = null;
                if (box.isVecInside(start)) {
                    candidate = start;
                } else if (box.isVecInside(end)) {
                    candidate = end;
                } else {
                    MovingObjectPosition intercept = box.calculateIntercept(start, end);
                    if (intercept != null) candidate = intercept.hitVec;
                }
                if (candidate == null) continue;
                double distance = start.distanceTo(candidate);
                if (segment < bestSegment || (segment == bestSegment && distance < bestDistance)) {
                    bestSegment = segment;
                    bestDistance = distance;
                    bestEntity = entity;
                    bestHitVec = candidate;
                }
                break;
            }
        }

        return bestEntity == null ? null : new EntityPathHit(bestEntity, bestHitVec, bestSegment);
    }

    private void renderPath(Minecraft minecraft, List<Vec3> points, float[] color) {
        RazorClient client = RazorClient.getInstance();
        FrameContext frame = client == null ? null : client.getModuleManager().getFrameContext();
        double viewerX = frame == null ? minecraft.getRenderManager().viewerPosX : frame.getCameraX();
        double viewerY = frame == null ? minecraft.getRenderManager().viewerPosY : frame.getCameraY();
        double viewerZ = frame == null ? minecraft.getRenderManager().viewerPosZ : frame.getCameraZ();

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        renderer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (Vec3 point : points) {
            renderer.pos(point.xCoord - viewerX, point.yCoord - viewerY, point.zCoord - viewerZ)
                .color(color[0], color[1], color[2], 0.95F)
                .endVertex();
        }
        tessellator.draw();
    }

    private float[] getAimingColor() {
        aimingColorBuffer[0] = aimingRed.getValue() / 255.0F;
        aimingColorBuffer[1] = aimingGreen.getValue() / 255.0F;
        aimingColorBuffer[2] = aimingBlue.getValue() / 255.0F;
        return aimingColorBuffer;
    }

    private float[] getTrajectoryColor() {
        trajectoryColorBuffer[0] = trajectoryRed.getValue() / 255.0F;
        trajectoryColorBuffer[1] = trajectoryGreen.getValue() / 255.0F;
        trajectoryColorBuffer[2] = trajectoryBlue.getValue() / 255.0F;
        return trajectoryColorBuffer;
    }

    private static final class ProjectileProperties {
        private final double velocity;
        private final double gravity;
        private final double drag;
        private final double startOffset;
        private final float pitchOffset;

        private ProjectileProperties(double velocity, double gravity, double drag, double startOffset, float pitchOffset) {
            this.velocity = velocity;
            this.gravity = gravity;
            this.drag = drag;
            this.startOffset = startOffset;
            this.pitchOffset = pitchOffset;
        }
    }

    private static final class SimulationResult {
        private final List<Vec3> points;
        private final Entity entityHit;

        private SimulationResult(List<Vec3> points, Entity entityHit) {
            this.points = points;
            this.entityHit = entityHit;
        }
    }

    private static final class EntityPathHit {
        private final Entity entity;
        private final Vec3 hitVec;
        private final int segmentIndex;

        private EntityPathHit(Entity entity, Vec3 hitVec, int segmentIndex) {
            this.entity = entity;
            this.hitVec = hitVec;
            this.segmentIndex = segmentIndex;
        }
    }
}
