package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.combat.CombatTargetService;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Client-side target selection and rotation smoothing for the live payload. */
public final class AimAssistModule extends Module {
    // Existing setting names are retained so saved configurations continue to load.
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 1, 20, 1, 4);
    private final NumberSetting randomization = new NumberSetting("Randomization", 0, 30, 1, 0);
    private final NumberSetting fov = new NumberSetting("FOV", 15, 360, 1, 90);
    private final DecimalSetting distance = new DecimalSetting("Distance", 1.0D, 10.0D, 0.5D, 4.5D);
    private final DecimalSetting minimumRange = new DecimalSetting("Minimum Range", 0.0D, 9.5D, 0.5D, 0.0D);
    private final EnumSetting<TargetType> targetType = new EnumSetting<TargetType>("Target Type", TargetType.values(), TargetType.PLAYERS);
    private final BooleanSetting clickAim = new BooleanSetting("Click Aim", true);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting targetInvis = new BooleanSetting("Target Invis", false);
    private final BooleanSetting breakBlocks = new BooleanSetting("Break Blocks", true);

    private final EnumSetting<AimMode> aimMode = new EnumSetting<AimMode>("Aim Mode", AimMode.values(), AimMode.REGULAR);
    private final EnumSetting<TargetMode> targetMode = new EnumSetting<TargetMode>("Target Mode", TargetMode.values(), TargetMode.SINGLE);
    private final EnumSetting<SortMode> sortMode = new EnumSetting<SortMode>("Sort Mode", SortMode.values(), SortMode.AIM_ANGLE);
    private final NumberSetting horizontalSpeed = new NumberSetting("Horizontal Speed", 10, 360, 1, 70);
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical Speed", 10, 360, 1, 50);
    private final NumberSetting horizontalMultipoint = new NumberSetting("Horizontal Multipoint", 0, 100, 1, 50);
    private final NumberSetting verticalMultipoint = new NumberSetting("Vertical Multipoint", 0, 100, 1, 50);
    private final NumberSetting prediction = new NumberSetting("Prediction", 0, 100, 1, 25);
    private final NumberSetting minimumFov = new NumberSetting("Minimum FOV", 0, 180, 1, 0);
    private final DecimalSetting lockedFovMultiplier = new DecimalSetting("Locked FOV Multiplier", 1.0D, 3.0D, 0.1D, 1.5D);
    private final BooleanSetting requireMouseMovement = new BooleanSetting("Require Mouse Movement", false);
    private final BooleanSetting requireSprinting = new BooleanSetting("Require Sprinting", false);
    private final BooleanSetting ignoreTeammates = new BooleanSetting("Ignore Teammates", true);
    private final BooleanSetting requireVisibility = new BooleanSetting("Require Visibility", false);
    private final BooleanSetting keepMoveDirection = new BooleanSetting("Keep Move Direction", true);
    private final BooleanSetting ignoreManualAim = new BooleanSetting("Ignore Manual Aim", false);

    private final Random random = getScope().getRandom();
    private EntityLivingBase lockedTarget;
    private long lastRenderUpdateNanos = -1L;
    private float lastObservedYaw;
    private float lastObservedPitch;
    private boolean haveObservedRotation;
    private float silentYaw;
    private float silentPitch;
    private boolean silentRotationInitialized;
    private volatile float desiredSilentYaw;
    private volatile float desiredSilentPitch;
    private volatile long desiredSilentAtNanos;
    private volatile boolean desiredSilentRotation;
    private String status = "Ready";

    public AimAssistModule() {
        super("AimAssist", "Smoothly nudges your aim toward selected targets.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(rotationSpeed);
        addSetting(randomization);
        addSetting(fov);
        addSetting(distance);
        addSetting(minimumRange);
        addSetting(targetType);
        addSetting(clickAim);
        addSetting(weaponOnly);
        addSetting(targetInvis);
        addSetting(breakBlocks);
        addSetting(aimMode);
        addSetting(targetMode);
        addSetting(sortMode);
        addSetting(horizontalSpeed);
        addSetting(verticalSpeed);
        addSetting(horizontalMultipoint);
        addSetting(verticalMultipoint);
        addSetting(prediction);
        addSetting(minimumFov);
        addSetting(lockedFovMultiplier);
        addSetting(requireMouseMovement);
        addSetting(requireSprinting);
        addSetting(ignoreTeammates);
        addSetting(requireVisibility);
        addSetting(keepMoveDirection);
        addSetting(ignoreManualAim);
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    @Override
    public void onSessionReset() {
        resetState();
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || aimMode.getValue() != AimMode.SILENT
                || !desiredSilentRotation) return;
        if (System.nanoTime() - desiredSilentAtNanos > 250_000_000L) {
            clearSilentRotation();
            return;
        }
        if (ClientRotationHelper.get().requestRotations(
                "AimAssist", 10, desiredSilentYaw, desiredSilentPitch)) {
            if (keepMoveDirection.isEnabled()) ClientRotationHelper.get().fixMovementInputs();
            status = "Silent";
        } else {
            status = "Suppressed: " + ClientRotationHelper.get().getRequestedOwner();
        }
    }

    @Override
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (!canAim(minecraft)) {
            resetTargetTiming();
            return;
        }

        float currentYaw = minecraft.thePlayer.rotationYaw;
        float currentPitch = minecraft.thePlayer.rotationPitch;
        boolean userMovedMouse = !haveObservedRotation
            || Math.abs(MathHelper.wrapAngleTo180_float(currentYaw - lastObservedYaw)) > 0.025F
            || Math.abs(currentPitch - lastObservedPitch) > 0.025F;
        lastObservedYaw = currentYaw;
        lastObservedPitch = currentPitch;
        haveObservedRotation = true;

        if (!shouldAssist(minecraft, userMovedMouse)) {
            resetTargetTiming();
            return;
        }

        boolean silent = aimMode.getValue() == AimMode.SILENT;
        if (silent && (isKillAuraOwningRotation() || (!ignoreManualAim.isEnabled() && userMovedMouse))) {
            clearSilentRotation();
            status = isKillAuraOwningRotation()
                ? "Suppressed: " + ClientRotationHelper.get().getRequestedOwner() : "Manual";
            return;
        }
        if (!silent) {
            clearSilentRotation();
        }

        TargetSnapshot target = selectTarget(minecraft, currentYaw, currentPitch);
        if (target == null) {
            lockedTarget = null;
            clearSilentRotation();
            status = "No target";
            return;
        }
        getContext().getTargetPublications().publish(getScope().getOwnerToken(), target.entity.getEntityId(), 50,
            getContext().getTick());

        float deltaSeconds = consumeDeltaSeconds();
        if (silent && !silentRotationInitialized) {
            silentYaw = currentYaw;
            silentPitch = currentPitch;
            silentRotationInitialized = true;
        }
        float rotationBaseYaw = silent ? silentYaw : currentYaw;
        float rotationBasePitch = silent ? silentPitch : currentPitch;
        Rotation rotation = rotateToward(rotationBaseYaw, rotationBasePitch, target.yaw, target.pitch, deltaSeconds);
        if (silent) {
            silentYaw = rotation.yaw;
            silentPitch = rotation.pitch;
            desiredSilentYaw = rotation.yaw;
            desiredSilentPitch = rotation.pitch;
            desiredSilentAtNanos = System.nanoTime();
            desiredSilentRotation = true;
            status = "Silent";
            return;
        }
        applyClientRotations(minecraft, rotation.yaw, rotation.pitch);
        status = target.entity.getName();
    }

    private TargetSnapshot selectTarget(Minecraft minecraft, float baseYaw, float basePitch) {
        double maximumDistance = distance.getValue();
        EntityLivingBase preferred = targetMode.getValue() == TargetMode.SINGLE ? lockedTarget : null;
        TargetSnapshot lockedSnapshot = preferred == null ? null : snapshotFor(minecraft, preferred, baseYaw, basePitch, maximumDistance, true);
        if (lockedSnapshot != null) {
            return lockedSnapshot;
        }

        TargetSnapshot best = null;
        for (EntityLivingBase living : CombatTargetService.candidates(minecraft)) {
            TargetSnapshot candidate = snapshotFor(minecraft, living, baseYaw, basePitch, maximumDistance, false);
            if (candidate != null && (best == null || compare(candidate, best) < 0)) {
                best = candidate;
            }
        }

        lockedTarget = targetMode.getValue() == TargetMode.SINGLE && best != null ? best.entity : null;
        return best;
    }

    private TargetSnapshot snapshotFor(Minecraft minecraft, EntityLivingBase candidate, float baseYaw, float basePitch,
            double maximumDistance, boolean isLocked) {
        if (!isValidTarget(minecraft, candidate, maximumDistance)) {
            return null;
        }

        Vec3 aimPoint = createAimPoint(minecraft, candidate);
        Rotation rotation = rotationsTo(minecraft, aimPoint);
        float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(rotation.yaw - baseYaw));
        float pitchDifference = Math.abs(rotation.pitch - basePitch);
        float maximumFov = fov.getValue() * (isLocked ? (float) lockedFovMultiplier.getValue() : 1.0F);
        maximumFov = Math.min(360.0F, maximumFov);
        if (yawDifference > maximumFov * 0.5F || yawDifference < minimumFov.getValue() * 0.5F) {
            return null;
        }

        double eyeDistance = minecraft.thePlayer.getPositionEyes(1.0F).distanceTo(aimPoint);
        if (eyeDistance < minimumRange.getValue() || eyeDistance > maximumDistance) {
            return null;
        }

        return new TargetSnapshot(candidate, rotation.yaw, rotation.pitch, eyeDistance, yawDifference, pitchDifference);
    }

    private int compare(TargetSnapshot left, TargetSnapshot right) {
        switch (sortMode.getValue()) {
            case DISTANCE:
                return Double.compare(left.distance, right.distance);
            case HEALTH:
                return Float.compare(left.entity.getHealth(), right.entity.getHealth());
            case HURT_TIME:
                return Integer.compare(right.entity.hurtTime, left.entity.hurtTime);
            case AIM_ANGLE:
            default:
                return Double.compare(left.aimAngle(), right.aimAngle());
        }
    }

    private boolean isValidTarget(Minecraft minecraft, EntityLivingBase candidate, double maximumDistance) {
        return CombatTargetService.isValid(minecraft, candidate, targetType.getValue().targetsPlayers(),
            targetType.getValue().targetsMobs(), targetInvis.isEnabled(), requireVisibility.isEnabled(),
            ignoreTeammates.isEnabled(), maximumDistance);
    }

    private Vec3 createAimPoint(Minecraft minecraft, EntityLivingBase entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        Vec3 eye = minecraft.thePlayer.getPositionEyes(1.0F);
        double closestX = clamp(eye.xCoord, box.minX, box.maxX);
        double closestY = clamp(eye.yCoord, box.minY, box.maxY);
        double closestZ = clamp(eye.zCoord, box.minZ, box.maxZ);
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        double horizontal = horizontalMultipoint.getValue() / 100.0D;
        double vertical = verticalMultipoint.getValue() / 100.0D;
        double predictionFactor = prediction.getValue() / 100.0D;
        double velocityX = entity.posX - entity.prevPosX;
        double velocityY = entity.posY - entity.prevPosY;
        double velocityZ = entity.posZ - entity.prevPosZ;
        return new Vec3(
            lerp(centerX, closestX, horizontal) + velocityX * predictionFactor,
            lerp(centerY, closestY, vertical) + velocityY * predictionFactor,
            lerp(centerZ, closestZ, horizontal) + velocityZ * predictionFactor
        );
    }

    private Rotation rotationsTo(Minecraft minecraft, Vec3 point) {
        Vec3 eye = minecraft.thePlayer.getPositionEyes(1.0F);
        double deltaX = point.xCoord - eye.xCoord;
        double deltaY = point.yCoord - eye.yCoord;
        double deltaZ = point.zCoord - eye.zCoord;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = MathHelper.wrapAngleTo180_float((float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0D));
        float pitch = clampPitch((float) -Math.toDegrees(Math.atan2(deltaY, horizontalDistance)));
        return new Rotation(yaw, pitch);
    }

    private Rotation rotateToward(float currentYaw, float currentPitch, float targetYaw, float targetPitch, float seconds) {
        float yawDelta = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDelta = targetPitch - currentPitch;
        float variance = randomization.getValue() / 100.0F;
        if (variance > 0.0F) {
            float jitter = variance * 0.7F;
            yawDelta += (random.nextFloat() - 0.5F) * jitter;
            pitchDelta += (random.nextFloat() - 0.5F) * jitter;
        }

        float rotationMultiplier = 0.45F + rotationSpeed.getValue() * 0.055F;
        float horizontalStep = horizontalSpeed.getValue() * rotationMultiplier * seconds;
        float verticalStep = verticalSpeed.getValue() * rotationMultiplier * seconds;
        switch (aimMode.getValue()) {
            case LOCK_ON:
                horizontalStep *= 2.25F;
                verticalStep *= 2.25F;
                break;
            case REGULAR:
                horizontalStep *= Math.min(1.0F, Math.abs(yawDelta) / 20.0F + 0.2F);
                verticalStep *= Math.min(1.0F, Math.abs(pitchDelta) / 20.0F + 0.2F);
                break;
            case LINEAR:
            default:
                break;
        }
        return new Rotation(
            currentYaw + clamp(yawDelta, -horizontalStep, horizontalStep),
            clampPitch(currentPitch + clamp(pitchDelta, -verticalStep, verticalStep))
        );
    }

    private boolean canAim(Minecraft minecraft) {
        return minecraft != null && minecraft.thePlayer != null && minecraft.theWorld != null && !minecraft.thePlayer.isDead
            && minecraft.currentScreen == null && minecraft.inGameHasFocus
            && (!weaponOnly.isEnabled() || isHoldingWeapon(minecraft));
    }

    private boolean shouldAssist(Minecraft minecraft, boolean userMovedMouse) {
        return (!breakBlocks.isEnabled() || !isBreakingBlock(minecraft))
            && (!clickAim.isEnabled() || Mouse.isButtonDown(0))
            && (!requireMouseMovement.isEnabled() || userMovedMouse)
            && (!requireSprinting.isEnabled() || minecraft.thePlayer.isSprinting());
    }

    private boolean isBreakingBlock(Minecraft minecraft) {
        MovingObjectPosition mouseOver = minecraft.objectMouseOver;
        return mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    private boolean isHoldingWeapon(Minecraft minecraft) {
        if (minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }
        Item item = minecraft.thePlayer.getHeldItem().getItem();
        if (item instanceof ItemSword || item == Items.stick) {
            return true;
        }
        String name = item.getUnlocalizedName();
        return name != null && name.contains("axe");
    }

    private void applyClientRotations(Minecraft minecraft, float yaw, float pitch) {
        minecraft.thePlayer.prevRotationYaw = minecraft.thePlayer.rotationYaw;
        minecraft.thePlayer.prevRotationPitch = minecraft.thePlayer.rotationPitch;
        minecraft.thePlayer.prevRotationYawHead = minecraft.thePlayer.rotationYawHead;
        minecraft.thePlayer.prevRenderYawOffset = minecraft.thePlayer.renderYawOffset;
        minecraft.thePlayer.rotationYaw = yaw;
        minecraft.thePlayer.rotationPitch = pitch;
        minecraft.thePlayer.rotationYawHead = yaw;
        minecraft.thePlayer.renderYawOffset = yaw;
    }

    private float consumeDeltaSeconds() {
        long now = System.nanoTime();
        if (lastRenderUpdateNanos <= 0L) {
            lastRenderUpdateNanos = now;
            return 1.0F / 60.0F;
        }
        float seconds = (float) ((now - lastRenderUpdateNanos) / 1_000_000_000.0D);
        lastRenderUpdateNanos = now;
        return clamp(seconds, 1.0F / 240.0F, 0.05F);
    }

    private void resetTargetTiming() {
        lockedTarget = null;
        lastRenderUpdateNanos = -1L;
        clearSilentRotation();
    }

    private void clearSilentRotation() {
        silentRotationInitialized = false;
        desiredSilentRotation = false;
        ClientRotationHelper.get().clearRequestedRotations("AimAssist");
    }

    private void resetState() {
        resetTargetTiming();
        haveObservedRotation = false;
        status = "Ready";
        getContext().getTargetPublications().clear(getScope().getOwnerToken());
    }

    @Override
    public String getHudInfo() {
        return status;
    }

    private boolean isKillAuraOwningRotation() {
        RazorClient client = RazorClient.getInstance();
        if (client == null) {
            return false;
        }
        KillAuraModule aura = client.getModuleManager().getModule(KillAuraModule.class);
        return aura != null && aura.isActivelyOwningRotation();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private static float clampPitch(float value) {
        return clamp(value, -90.0F, 90.0F);
    }

    private static final class Rotation {
        private final float yaw;
        private final float pitch;

        private Rotation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class TargetSnapshot {
        private final EntityLivingBase entity;
        private final float yaw;
        private final float pitch;
        private final double distance;
        private final float yawDifference;
        private final float pitchDifference;

        private TargetSnapshot(EntityLivingBase entity, float yaw, float pitch, double distance, float yawDifference, float pitchDifference) {
            this.entity = entity;
            this.yaw = yaw;
            this.pitch = pitch;
            this.distance = distance;
            this.yawDifference = yawDifference;
            this.pitchDifference = pitchDifference;
        }

        private double aimAngle() {
            return yawDifference * yawDifference + pitchDifference * pitchDifference * 0.35D;
        }
    }

    private enum AimMode {
        REGULAR("Regular"), LINEAR("Linear"), LOCK_ON("Lock On"), SILENT("Silent");
        private final String label;
        AimMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private enum TargetMode {
        SINGLE("Single"), SWITCH("Switch");
        private final String label;
        TargetMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private enum SortMode {
        AIM_ANGLE("Aim Angle"), DISTANCE("Distance"), HEALTH("Health"), HURT_TIME("Hurt Time");
        private final String label;
        SortMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private enum TargetType {
        PLAYERS("Players", true, false), MOBS("Mobs", false, true), BOTH("Players + Mobs", true, true);
        private final String label;
        private final boolean players;
        private final boolean mobs;
        TargetType(String label, boolean players, boolean mobs) { this.label = label; this.players = players; this.mobs = mobs; }
        private boolean targetsPlayers() { return players; }
        private boolean targetsMobs() { return mobs; }
        @Override public String toString() { return label; }
    }
}
