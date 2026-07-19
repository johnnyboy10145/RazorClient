package com.razorclient.combat;

import com.razorclient.RazorClient;
import com.razorclient.event.ClientRotationEvent;
import com.razorclient.event.JumpEvent;
import com.razorclient.event.StrafeEvent;
import com.razorclient.runtime.ResourceArbiter;
import com.razorclient.runtime.OwnerToken;
import com.razorclient.feature.module.Module;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class ClientRotationHelper {
    private static final ClientRotationHelper INSTANCE = new ClientRotationHelper();

    private final Minecraft minecraft = Minecraft.getMinecraft();

    private Float serverYaw;
    private Float serverPitch;
    private volatile RotationSnapshot publishedRotation;
    private volatile boolean setRotations;
    private boolean rotationsUpdatedThisTick;
    private float savedYaw;
    private float savedPitch;
    private float savedPrevYaw;
    private float savedPrevPitch;

    public boolean swappedForMouseOver;
    private boolean swappedForWalkingUpdate;
    private volatile String requestedOwner = "None";
    private volatile OwnerToken requestedOwnerToken;
    private volatile int requestedPriority = Integer.MIN_VALUE;
    private ResourceArbiter.Lease rotationLease;
    private ResourceArbiter.Lease modelRotationLease;
    private EntityPlayerSP modelRotationPlayer;
    private boolean modelSwapActive;
    private float savedYawHead;
    private float savedPrevYawHead;
    private float savedYawOffset;
    private float savedPrevYawOffset;
    private float savedModelPitch;
    private float savedPrevModelPitch;
    private Field renderPlayerField;
    private boolean registered;

    private ClientRotationHelper() {
    }

    public static ClientRotationHelper get() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (registered) return;
        MinecraftForge.EVENT_BUS.register(this);
        registered = true;
    }

    public synchronized void stop() {
        clearRequestedRotations();
        if (!registered) return;
        MinecraftForge.EVENT_BUS.unregister(this);
        registered = false;
    }

    public static float unwrapYaw(float yaw, float previousYaw) {
        return previousYaw + ((((yaw - previousYaw + 180.0F) % 360.0F) + 360.0F) % 360.0F - 180.0F);
    }

    public void onRunTickStart() {
        restoreModelSwap();
        closeModelRotationLease();
        ResourceArbiter.Lease previousRotationLease = rotationLease;
        if (previousRotationLease != null && previousRotationLease.isValid()) previousRotationLease.close();
        if (minecraft.thePlayer != null && !Float.isFinite(KillAuraRotationUtils.serverRotations[0])) {
            KillAuraRotationUtils.serverRotations[0] = minecraft.thePlayer.rotationYaw;
            KillAuraRotationUtils.serverRotations[1] = minecraft.thePlayer.rotationPitch;
        }

        serverYaw = null;
        serverPitch = null;
        publishedRotation = null;
        setRotations = false;
        rotationsUpdatedThisTick = false;
        swappedForMouseOver = false;
        swappedForWalkingUpdate = false;
        requestedOwner = "None";
        requestedOwnerToken = null;
        requestedPriority = Integer.MIN_VALUE;
        rotationLease = null;
    }

    public void clearRequestedRotations() {
        restoreModelSwap();
        closeModelRotationLease();
        ResourceArbiter.Lease lease = rotationLease;
        if (lease != null) lease.close();
        serverYaw = null;
        serverPitch = null;
        publishedRotation = null;
        setRotations = false;
        requestedOwner = "None";
        requestedOwnerToken = null;
        requestedPriority = Integer.MIN_VALUE;
        rotationLease = null;
    }

    public void clearRequestedRotations(String owner) {
        if (owner == null || !owner.equals(requestedOwner)) return;
        restoreModelSwap();
        closeModelRotationLease();
        ResourceArbiter arbiter = getArbiter();
        if (arbiter != null) arbiter.releaseOwner(requestedOwnerToken, ResourceArbiter.Resource.SERVER_ROTATION);
        serverYaw = null;
        serverPitch = null;
        publishedRotation = null;
        setRotations = false;
        requestedOwner = "None";
        requestedOwnerToken = null;
        requestedPriority = Integer.MIN_VALUE;
        rotationLease = null;
    }

    public boolean requestRotations(String owner, int priority, float yaw, float pitch) {
        return requestRotations(findOwnerToken(owner), priority, yaw, pitch);
    }

    public boolean requestRotations(OwnerToken owner, int priority, float yaw, float pitch) {
        if (owner == null || priority < requestedPriority
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return false;
        }
        ResourceArbiter arbiter = getArbiter();
        if (arbiter != null) {
            ResourceArbiter.Lease lease = arbiter.acquire(ResourceArbiter.Resource.SERVER_ROTATION,
                owner, priority, 1, null);
            if (lease == null) return false;
            rotationLease = lease;
        }
        requestedOwnerToken = owner;
        requestedOwner = owner.getModuleName();
        requestedPriority = priority;
        serverYaw = Float.valueOf(yaw);
        serverPitch = Float.valueOf(pitch);
        publishedRotation = new RotationSnapshot(owner, yaw, pitch);
        setRotations = true;
        return true;
    }

    public String getRequestedOwner() {
        return requestedOwner;
    }

    public void updateServerRotations() {
        if (minecraft.thePlayer == null || rotationsUpdatedThisTick) {
            return;
        }

        rotationsUpdatedThisTick = true;
        if (!Float.isFinite(KillAuraRotationUtils.serverRotations[0])) {
            KillAuraRotationUtils.serverRotations[0] = minecraft.thePlayer.rotationYaw;
            KillAuraRotationUtils.serverRotations[1] = minecraft.thePlayer.rotationPitch;
        }

        ClientRotationEvent event = new ClientRotationEvent(serverYaw, serverPitch);
        MinecraftForge.EVENT_BUS.post(event);
        serverYaw = event.yaw;
        serverPitch = event.pitch;
        if ((serverYaw != null && !Float.isFinite(serverYaw.floatValue()))
                || (serverPitch != null && !Float.isFinite(serverPitch.floatValue()))) {
            serverYaw = null;
            serverPitch = null;
            publishedRotation = null;
            setRotations = false;
            return;
        }
        if (serverYaw == null && serverPitch == null) {
            publishedRotation = null;
            setRotations = false;
            return;
        }

        float baseYaw = Float.isFinite(KillAuraRotationUtils.serverRotations[0])
            ? KillAuraRotationUtils.serverRotations[0] : minecraft.thePlayer.rotationYaw;
        float basePitch = Float.isFinite(KillAuraRotationUtils.serverRotations[1])
            ? KillAuraRotationUtils.serverRotations[1] : minecraft.thePlayer.rotationPitch;
        float[] fixed = KillAuraRotationUtils.fixRotation(
            serverYaw == null ? minecraft.thePlayer.rotationYaw : serverYaw.floatValue(),
            serverPitch == null ? minecraft.thePlayer.rotationPitch : serverPitch.floatValue(),
            baseYaw,
            basePitch
        );
        if (fixed == null || fixed.length < 2 || !Float.isFinite(fixed[0]) || !Float.isFinite(fixed[1])) {
            serverYaw = null;
            serverPitch = null;
            publishedRotation = null;
            setRotations = false;
            return;
        }
        if (serverYaw != null) {
            serverYaw = Float.valueOf(fixed[0]);
        }
        if (serverPitch != null) {
            serverPitch = Float.valueOf(fixed[1]);
        }
        if ((serverYaw != null && Float.isFinite(serverYaw.floatValue()) && serverYaw.floatValue() != minecraft.thePlayer.rotationYaw)
            || (serverPitch != null && Float.isFinite(serverPitch.floatValue()) && serverPitch.floatValue() != minecraft.thePlayer.rotationPitch)) {
            setRotations = true;
        }
        if (setRotations) {
            float publishedYaw = serverYaw == null ? minecraft.thePlayer.rotationYaw : serverYaw.floatValue();
            float publishedPitch = serverPitch == null ? minecraft.thePlayer.rotationPitch : serverPitch.floatValue();
            if (Float.isFinite(publishedYaw) && Float.isFinite(publishedPitch)) {
                publishedRotation = new RotationSnapshot(requestedOwnerToken, publishedYaw, publishedPitch);
            } else {
                publishedRotation = null;
                setRotations = false;
            }
        }
    }

    public void onWalkingUpdatePre(Entity entity) {
        if (entity == null || minecraft.thePlayer == null || entity != minecraft.thePlayer) {
            return;
        }

        if (setRotations) {
            float yaw = serverYaw != null && Float.isFinite(serverYaw.floatValue()) ? serverYaw.floatValue() : entity.rotationYaw;
            float pitch = serverPitch != null && Float.isFinite(serverPitch.floatValue()) ? serverPitch.floatValue() : entity.rotationPitch;
            beginSwap(entity, yaw, pitch, true);
            swappedForWalkingUpdate = true;
            KillAuraRotationUtils.serverRotations[0] = yaw;
            KillAuraRotationUtils.serverRotations[1] = pitch;
            return;
        }

        KillAuraRotationUtils.serverRotations[0] = entity.rotationYaw;
        KillAuraRotationUtils.serverRotations[1] = entity.rotationPitch;
    }

    public boolean isActive() {
        RotationSnapshot snapshot = publishedRotation;
        return isActive(snapshot);
    }

    private boolean isActive(RotationSnapshot snapshot) {
        if (!setRotations || snapshot == null || snapshot != publishedRotation) return false;
        ResourceArbiter arbiter = getArbiter();
        return arbiter == null || arbiter.isOwner(ResourceArbiter.Resource.SERVER_ROTATION, snapshot.owner);
    }

    public Float getServerYaw() {
        return serverYaw;
    }

    public Float getServerPitch() {
        return serverPitch;
    }

    /** Applies the active silent rotation to the packet without changing the local camera. */
    public void rewriteMovementPacket(C03PacketPlayer packet) {
        RotationSnapshot snapshot = publishedRotation;
        if (packet == null || !isActive(snapshot)) return;
        packet.yaw = snapshot.yaw;
        packet.pitch = MathHelper.clamp_float(snapshot.pitch, -90.0F, 90.0F);
        packet.rotating = true;
    }

    public void beginSwap(Entity entity, float yaw, float pitch, boolean swapPitch) {
        savedYaw = entity.rotationYaw;
        savedPrevYaw = entity.prevRotationYaw;
        savedPitch = entity.rotationPitch;
        savedPrevPitch = entity.prevRotationPitch;

        entity.rotationYaw = yaw;
        entity.prevRotationYaw = yaw;
        if (swapPitch) {
            entity.rotationPitch = pitch;
            entity.prevRotationPitch = pitch;
        }
    }

    public void endSwap(Entity entity) {
        entity.rotationYaw = savedYaw;
        entity.prevRotationYaw = savedPrevYaw;
        entity.rotationPitch = savedPitch;
        entity.prevRotationPitch = savedPrevPitch;
    }

    public void onWalkingUpdatePost(Entity entity) {
        if (!swappedForWalkingUpdate || entity == null) {
            return;
        }

        endSwap(entity);
        swappedForWalkingUpdate = false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderPlayerPre(Event event) {
        if (event == null || !"net.minecraftforge.client.event.RenderPlayerEvent$Pre".equals(event.getClass().getName())) {
            return;
        }
        restoreModelSwap();
        RotationSnapshot snapshot = publishedRotation;
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null || !isActive(snapshot) || resolveRenderPlayer(event) != player) return;

        ResourceArbiter arbiter = getArbiter();
        if (arbiter != null) {
            modelRotationLease = arbiter.acquire(ResourceArbiter.Resource.MODEL_ROTATION,
                snapshot.owner, requestedPriority, 2, new Runnable() {
                    @Override public void run() { restoreModelSwap(); }
                });
            if (modelRotationLease == null) return;
        }

        modelRotationPlayer = player;
        savedYawHead = player.rotationYawHead;
        savedPrevYawHead = player.prevRotationYawHead;
        savedYawOffset = player.renderYawOffset;
        savedPrevYawOffset = player.prevRenderYawOffset;
        savedModelPitch = player.rotationPitch;
        savedPrevModelPitch = player.prevRotationPitch;

        float modelYaw = unwrapYaw(snapshot.yaw, player.rotationYawHead);
        float previousModelYaw = unwrapYaw(player.prevRotationYawHead, modelYaw);
        player.rotationYawHead = modelYaw;
        player.prevRotationYawHead = previousModelYaw;
        player.renderYawOffset = modelYaw;
        player.prevRenderYawOffset = previousModelYaw;
        player.rotationPitch = MathHelper.clamp_float(snapshot.pitch, -90.0F, 90.0F);
        modelSwapActive = true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderPlayerPost(Event event) {
        if (event != null && "net.minecraftforge.client.event.RenderPlayerEvent$Post".equals(event.getClass().getName())
                && resolveRenderPlayer(event) == modelRotationPlayer) {
            restoreModelSwap();
        }
    }

    private void restoreModelSwap() {
        EntityPlayerSP player = modelRotationPlayer;
        if (modelSwapActive && player != null) {
            player.rotationYawHead = savedYawHead;
            player.prevRotationYawHead = savedPrevYawHead;
            player.renderYawOffset = savedYawOffset;
            player.prevRenderYawOffset = savedPrevYawOffset;
            player.rotationPitch = savedModelPitch;
            player.prevRotationPitch = savedPrevModelPitch;
        }
        modelSwapActive = false;
        modelRotationPlayer = null;
    }

    private void closeModelRotationLease() {
        ResourceArbiter.Lease lease = modelRotationLease;
        modelRotationLease = null;
        if (lease != null && lease.isValid()) lease.close();
    }

    private Object resolveRenderPlayer(Event event) {
        try {
            if (renderPlayerField == null
                    || !renderPlayerField.getDeclaringClass().isAssignableFrom(event.getClass())) {
                renderPlayerField = event.getClass().getField("entityPlayer");
                renderPlayerField.setAccessible(true);
            }
            return renderPlayerField.get(event);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public void fixMovementInputs() {
        if (minecraft.thePlayer == null || minecraft.thePlayer.movementInput == null || !canFixMovement()) {
            return;
        }

        float forward = minecraft.thePlayer.movementInput.moveForward;
        float strafe = minecraft.thePlayer.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) {
            return;
        }

        float sneakMultiplier = minecraft.thePlayer.movementInput.sneak ? 0.3F : 1.0F;
        double angle = MathHelper.wrapAngleTo180_double(Math.toDegrees(getDirection(minecraft.thePlayer.rotationYaw, forward, strafe)));
        float closestForward = 0.0F;
        float closestStrafe = 0.0F;
        float closestDifference = Float.MAX_VALUE;

        for (float predictedForwardRaw = -1.0F; predictedForwardRaw <= 1.0F; predictedForwardRaw += 1.0F) {
            for (float predictedStrafeRaw = -1.0F; predictedStrafeRaw <= 1.0F; predictedStrafeRaw += 1.0F) {
                if (predictedForwardRaw == 0.0F && predictedStrafeRaw == 0.0F) {
                    continue;
                }

                float predictedForward = predictedForwardRaw * sneakMultiplier;
                float predictedStrafe = predictedStrafeRaw * sneakMultiplier;
                double predictedAngle = MathHelper.wrapAngleTo180_double(Math.toDegrees(getDirection(serverYaw.floatValue(), predictedForward, predictedStrafe)));
                double difference = Math.abs(MathHelper.wrapAngleTo180_double(angle - predictedAngle));
                if (difference < closestDifference) {
                    closestDifference = (float) difference;
                    closestForward = predictedForward;
                    closestStrafe = predictedStrafe;
                }
            }
        }

        minecraft.thePlayer.movementInput.moveForward = closestForward;
        minecraft.thePlayer.movementInput.moveStrafe = closestStrafe;
    }

    @SubscribeEvent
    public void onStrafe(StrafeEvent event) {
        if (canFixMovement()) {
            event.setYaw(serverYaw.floatValue());
        }
    }

    @SubscribeEvent
    public void onJump(JumpEvent event) {
        if (canFixMovement()) {
            event.setYaw(serverYaw.floatValue());
        }
    }

    private boolean canFixMovement() {
        return isActive() && serverYaw != null && Float.isFinite(serverYaw.floatValue());
    }

    private static final class RotationSnapshot {
        private final OwnerToken owner;
        private final float yaw;
        private final float pitch;

        private RotationSnapshot(OwnerToken owner, float yaw, float pitch) {
            this.owner = owner;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static ResourceArbiter getArbiter() {
        RazorClient client = RazorClient.getInstance();
        return client == null ? null : client.getModuleManager().getResourceArbiter();
    }

    private static OwnerToken findOwnerToken(String owner) {
        RazorClient client = RazorClient.getInstance();
        if (client == null || owner == null) return null;
        for (Module module : client.getModuleManager().getModules()) {
            if (owner.equals(module.getName())) return module.getScope().getOwnerToken();
        }
        return null;
    }

    private static double getDirection(float rotationYaw, double moveForward, double moveStrafing) {
        if (moveForward < 0.0D) {
            rotationYaw += 180.0F;
        }

        float forward = 1.0F;
        if (moveForward < 0.0D) {
            forward = -0.5F;
        } else if (moveForward > 0.0D) {
            forward = 0.5F;
        }

        if (moveStrafing > 0.0D) {
            rotationYaw -= 90.0F * forward;
        }
        if (moveStrafing < 0.0D) {
            rotationYaw += 90.0F * forward;
        }

        return Math.toRadians(rotationYaw);
    }
}
