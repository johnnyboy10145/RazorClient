package com.razorclient.feature.module.impl;

import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.event.ClientRotationEvent;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.impl.clutch.ClutchBridgePlanner;
import com.razorclient.feature.module.impl.clutch.ClutchCandidate;
import com.razorclient.feature.module.impl.clutch.ClutchCandidateScanner;
import com.razorclient.feature.module.impl.clutch.ClutchConfirmationTracker;
import com.razorclient.feature.module.impl.clutch.ClutchPhase;
import com.razorclient.feature.module.impl.clutch.ClutchPlacementExecutor;
import com.razorclient.feature.module.impl.clutch.ClutchPredictor;
import com.razorclient.feature.module.impl.clutch.ClutchSession;
import com.razorclient.feature.module.impl.clutch.ClutchSilentRotationController;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.runtime.ResourceArbiter;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/** Client-thread-only emergency placement and short bridge recovery module. */
public final class ClutchModule extends Module {
    private final Minecraft mc = Minecraft.getMinecraft();

    private final EnumSetting<Trigger> trigger = new EnumSetting<Trigger>("Trigger", Trigger.values(), Trigger.PREDICTED_DANGER);
    private final DecimalSetting blocks = new DecimalSetting("Blocks", 1.0D, 50.0D, 1.0D, 4.0D);
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false);
    private final BooleanSetting rotateBack = new BooleanSetting("Rotate Back", true);
    private final BooleanSetting returnToSlot = new BooleanSetting("Return To Slot", true);
    private final NumberSetting clutchMoveDelay = new NumberSetting("Clutch Move Delay", 0, 20, 1, 0);
    private final NumberSetting maxBlocks = new NumberSetting("Max Blocks", 1, 64, 1, 10);
    private final DecimalSetting rotationSpeed = new DecimalSetting("Rotation Speed", 10.0D, 120.0D, 1.0D, 60.0D);
    private final EnumSetting<FilterMode> filterMode = new EnumSetting<FilterMode>("Filter Mode", FilterMode.values(), FilterMode.NONE);
    private final NumberSetting range = new NumberSetting("Range", 1, 8, 1, 4);
    private final NumberSetting fov = new NumberSetting("FOV", 15, 180, 5, 180);
    private final NumberSetting minimumHeight = new NumberSetting("Minimum Height", 1, 20, 1, 1);
    private final NumberSetting clickSpeed = new NumberSetting("Click Speed", 1, 20, 1, 12);
    private final NumberSetting randomization = new NumberSetting("Randomization", 0, 100, 1, 0);
    private final EnumSetting<SelectBlocksMode> selectBlocks = new EnumSetting<SelectBlocksMode>("Select Blocks", SelectBlocksMode.values(), SelectBlocksMode.ALWAYS);
    private final BooleanSetting onlyPlaceSideways = new BooleanSetting("Only Place Sideways", false);
    private final NumberSetting aimAcceleration = new NumberSetting("Aim Acceleration", 0, 180, 1, 0);
    private final NumberSetting accelerationStrength = new NumberSetting("Acceleration Strength", 0, 100, 1, 50);
    private final BooleanSetting multipoint = new BooleanSetting("Multipoint", false);
    private final NumberSetting snapBackDelay = new NumberSetting("Snap Back Delay", 0, 20, 1, 0);
    private final NumberSetting snapBackDuration = new NumberSetting("Snap Back Duration", 0, 20, 1, 0);
    private final BooleanSetting keepJumpDirection = new BooleanSetting("Keep Jump Direction", false);
    private final BooleanSetting disableAfterwards = new BooleanSetting("Disable Afterwards", false);
    private final BooleanSetting onlyMidAir = new BooleanSetting("Only Mid-Air", false);
    private final BooleanSetting recentlyDamaged = new BooleanSetting("Recently Damaged", false);
    private final BooleanSetting movingBackwards = new BooleanSetting("Moving Backwards", false);
    private final EnumSetting<RecoveryMode> recoveryMode = new EnumSetting<RecoveryMode>("Recovery Mode", RecoveryMode.values(), RecoveryMode.EMERGENCY_BRIDGE);
    private final NumberSetting predictionTicks = new NumberSetting("Prediction Ticks", 1, 8, 1, 4);
    private final NumberSetting confirmationTicks = new NumberSetting("Confirmation Ticks", 1, 5, 1, 2);

    private final Random random = getScope().getRandom();
    private final ClutchSession session = new ClutchSession();
    private final ClutchPredictor predictor = new ClutchPredictor();
    private final ClutchCandidateScanner scanner = new ClutchCandidateScanner();
    private final ClutchBridgePlanner bridge = new ClutchBridgePlanner();
    private final ClutchConfirmationTracker confirmation = new ClutchConfirmationTracker();
    private final ClutchPlacementExecutor placementExecutor = new ClutchPlacementExecutor();
    private final ClutchSilentRotationController silentRotation = new ClutchSilentRotationController();
    private final BlockPos.MutableBlockPos triggerCursor = new BlockPos.MutableBlockPos();

    private ResourceArbiter.Lease slotLease;
    private ResourceArbiter.Lease useActionLease;
    private ResourceArbiter.Lease serverRotationLease;
    private ClutchCandidate activeCandidate;
    private boolean forgeRegistered;
    private boolean clutching;
    private boolean returningToCamera;
    private boolean slotSwitchPending;
    private int lastHeldBlockSlot = -1;
    private int groundedTicks;
    private int rotationHeldTicks;
    private int snapBackDelayTicks;
    private int snapBackDurationTicks;
    private float savedCameraYaw;
    private float savedCameraPitch;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private boolean rotationActive;
    private long lastAttemptNanos;
    private long nextAttemptDelayNanos;
    private BlockPos lastAttemptNeighbor;
    private EnumFacing lastAttemptFace;

    public ClutchModule() {
        super("Clutch", "Places emergency blocks and builds a short recovery path", Category.PLAYER,
            Keyboard.KEY_NONE);
        blocks.setVisibility(() -> trigger.getValue() == Trigger.FALL_DISTANCE);
        rotateBack.setVisibility(() -> !silentAim.isEnabled());
        clutchMoveDelay.setVisibility(() -> false);
        filterMode.setVisibility(() -> false);
        addSetting(trigger);
        addSetting(blocks);
        addSetting(silentAim);
        addSetting(rotateBack);
        addSetting(returnToSlot);
        addSetting(clutchMoveDelay);
        addSetting(maxBlocks);
        addSetting(rotationSpeed);
        addSetting(filterMode);
        addSetting(range);
        addSetting(fov);
        addSetting(minimumHeight);
        addSetting(clickSpeed);
        addSetting(randomization);
        addSetting(selectBlocks);
        addSetting(onlyPlaceSideways);
        addSetting(aimAcceleration);
        addSetting(accelerationStrength);
        addSetting(multipoint);
        addSetting(snapBackDelay);
        addSetting(snapBackDuration);
        addSetting(keepJumpDirection);
        addSetting(disableAfterwards);
        addSetting(onlyMidAir);
        addSetting(recentlyDamaged);
        addSetting(movingBackwards);
        addSetting(recoveryMode);
        addSetting(predictionTicks);
        addSetting(confirmationTicks);
    }

    @Override
    protected void onEnable() {
        resetState();
        registerForge();
    }

    @Override
    protected void onDisable() {
        unregisterForge();
        abortClutch();
    }

    @Override
    public void onSessionReset() {
        abortClutch();
    }

    @Override
    public void onInputContextLost() {
        abortClutch();
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        silentRotation.restoreRenderSwap();
        long now = System.nanoTime();
        EntityPlayerSP player = mc.thePlayer;
        if (!isPlayerReady()) {
            abortClutch();
            return;
        }
        if (session.isActive() && !session.matches(mc.theWorld, player.getEntityId())) {
            abortClutch();
            return;
        }
        if (slotLease != null && !slotLease.renew(2)) {
            slotLease = null;
            abortClutch();
            return;
        }
        if (serverRotationLease != null && !serverRotationLease.renew(2)) {
            serverRotationLease = null;
            if (session.isActive() && session.markBlocked(ClutchSession.BlockedReason.SUPPRESSED,
                    "Suppressed", now)) abortClutch();
            return;
        }
        if (returningToCamera) {
            handleSnapBack(player);
            return;
        }
        if (player.onGround) {
            handleGroundState(player, now);
            return;
        }
        groundedTicks = 0;
        if (player.motionY >= 0.0D) {
            abortClutch();
            return;
        }
        if (session.getPhase() == ClutchPhase.CONFIRMING) {
            processConfirmation(player, now);
            return;
        }
        if (session.getPhase() == ClutchPhase.CLEANUP) {
            finishRecovery(player);
            return;
        }
        if (session.getPhase() == ClutchPhase.IDLE && !beginIfRequired(player, now)) return;
        if (session.getBlocksPlaced() >= maxBlocks.getValue()) {
            session.transition(ClutchPhase.CLEANUP, "Limit", now);
            return;
        }
        if (!ensureHoldingBlock(player)) {
            if (session.markBlocked(ClutchSession.BlockedReason.NO_BLOCK, "No Block", now)) abortClutch();
            return;
        }
        session.clearBlocked(ClutchSession.BlockedReason.NO_BLOCK);

        if (activeCandidate == null || !isCandidateValid(player, activeCandidate)) {
            activeCandidate = selectCandidate(player);
            if (activeCandidate != null) {
                activeCandidate = scanner.randomizeAim(activeCandidate, random, randomization.getValue());
                session.clearBlocked(ClutchSession.BlockedReason.NO_FACE);
            }
        }
        if (activeCandidate == null) {
            if (session.markBlocked(ClutchSession.BlockedReason.NO_FACE, "No Face", now)) abortClutch();
            return;
        }
        if (!acquireServerRotationLease()) {
            if (session.markBlocked(ClutchSession.BlockedReason.SUPPRESSED, "Suppressed", now)) abortClutch();
            return;
        }
        session.clearBlocked(ClutchSession.BlockedReason.SUPPRESSED);

        setRotationTarget(activeCandidate.yawFrom(player), activeCandidate.pitchFrom(player));
        stepRotation(resolveAimSpeed());
        if (silentAim.isEnabled()) silentRotation.update(getScope(), player, currentYaw, currentPitch);
        else {
            silentRotation.clear();
            applyVisibleRotation(player);
        }
        session.transition(session.getPhase() == ClutchPhase.BRIDGING ? ClutchPhase.BRIDGING : ClutchPhase.AIMING,
            session.getPhase() == ClutchPhase.BRIDGING ? bridge.status() : "Aiming", now);
        if (!hasReachedTarget(2.0F)) {
            rotationHeldTicks = 0;
            return;
        }
        rotationHeldTicks = Math.min(2, rotationHeldTicks + 1);
        if (rotationHeldTicks < 2 || !canPlaceNow(now) || !canRetryCandidate(activeCandidate, now)) return;

        MovingObjectPosition hit = rayTraceAtRotation(player, getReach(player), currentYaw, currentPitch);
        if (!matchesCandidate(hit, activeCandidate)) {
            if (session.markBlocked(ClutchSession.BlockedReason.NO_FACE, "No Face", now)) abortClutch();
            return;
        }
        session.clearBlocked(ClutchSession.BlockedReason.NO_FACE);
        if (!acquireUseActionLease()) {
            if (session.markBlocked(ClutchSession.BlockedReason.SUPPRESSED, "Suppressed", now)) abortClutch();
            return;
        }
        session.clearBlocked(ClutchSession.BlockedReason.SUPPRESSED);
        session.transition(ClutchPhase.PLACING, "Placing", now);
        ItemStack held = player.getHeldItem();
        int slot = player.inventory.currentItem;
        int count = held == null ? 0 : held.stackSize;
        recordAttempt(activeCandidate, now);
        boolean invoked;
        try {
            invoked = placementExecutor.attempt(mc, player, hit, activeCandidate);
        } finally {
            releaseUseActionLease();
        }
        if (!invoked) {
            activeCandidate = null;
            if (session.placementFailed(now)) abortClutch();
            return;
        }
        confirmation.start(activeCandidate, mc.theWorld, player.getEntityId(), slot, count,
            recoveryMode.getValue() == RecoveryMode.EMERGENCY_BRIDGE, confirmationTicks.getValue());
        session.transition(ClutchPhase.CONFIRMING, "Confirming", now);
    }

    private boolean beginIfRequired(EntityPlayerSP player, long now) {
        if (!triggerMet(player) || !conditionsMet(player)) return false;
        if (!hasBlocks(player)) return false;
        if (!acquireSlotLease(player)) return false;
        session.begin(mc.theWorld, player.getEntityId(), now);
        clutching = true;
        savedCameraYaw = player.rotationYaw;
        savedCameraPitch = player.rotationPitch;
        currentYaw = player.rotationYaw;
        currentPitch = player.rotationPitch;
        lastHeldBlockSlot = heldBlockSlot(player);
        return true;
    }

    private void processConfirmation(EntityPlayerSP player, long now) {
        ClutchCandidate attempted = confirmation.getCandidate();
        boolean bridgeAllowed = confirmation.isBridgeAllowed();
        ClutchConfirmationTracker.Result result = confirmation.poll(mc.theWorld, player);
        if (result == ClutchConfirmationTracker.Result.PENDING) return;
        confirmation.clear();
        activeCandidate = null;
        if (result == ClutchConfirmationTracker.Result.INVALID_SESSION
                || result == ClutchConfirmationTracker.Result.NONE) {
            abortClutch();
            return;
        }
        if (result == ClutchConfirmationTracker.Result.FAILED) {
            if (session.getPlacementFailures() >= 2) bridge.clear();
            if (session.placementFailed(now)) {
                abortClutch();
                return;
            }
            session.transition(session.isEmergencyConfirmed() ? ClutchPhase.BRIDGING : ClutchPhase.ARMED,
                session.getPlacementFailures() >= 3 ? "Replanning" : "Retry", now);
            return;
        }

        boolean wasEmergencyConfirmed = session.isEmergencyConfirmed();
        session.placementConfirmed();
        lastHeldBlockSlot = heldBlockSlot(player);
        if (wasEmergencyConfirmed) bridge.advance(attempted);
        boolean supported = predictor.hasCollisionSupport(mc.theWorld, player, player.getEntityBoundingBox());
        if (!bridgeAllowed || supported || session.getBlocksPlaced() >= maxBlocks.getValue()
                || (wasEmergencyConfirmed && bridge.isComplete())) {
            session.transition(ClutchPhase.CLEANUP, "Saved", now);
            return;
        }
        if (!wasEmergencyConfirmed || bridge.isEmpty()) refreshBridge(player);
        session.transition(ClutchPhase.BRIDGING, bridge.status(), now);
    }

    private ClutchCandidate selectCandidate(EntityPlayerSP player) {
        if (!session.isEmergencyConfirmed()) {
            return scanner.findEmergency(mc.theWorld, player, range.getValue(), session.getBlocksPlaced(),
                getReach(player), onlyPlaceSideways.isEnabled(), fov.getValue(), multipoint.isEnabled());
        }
        if (recoveryMode.getValue() == RecoveryMode.EMERGENCY_ONLY) return null;
        if (bridge.isEmpty() || bridge.isComplete()) refreshBridge(player);
        ClutchCandidate candidate = bridge.next(mc.theWorld, player, scanner,
            onlyPlaceSideways.isEnabled(), multipoint.isEnabled());
        if (candidate == null) {
            refreshBridge(player);
            candidate = bridge.next(mc.theWorld, player, scanner,
                onlyPlaceSideways.isEnabled(), multipoint.isEnabled());
        }
        return candidate;
    }

    private void refreshBridge(EntityPlayerSP player) {
        int remaining = Math.max(0, maxBlocks.getValue() - session.getBlocksPlaced());
        bridge.refresh(mc.theWorld, player, scanner, range.getValue(), remaining,
            countAvailableBlocks(player), getReach(player), onlyPlaceSideways.isEnabled(),
            fov.getValue(), multipoint.isEnabled());
    }

    private boolean isCandidateValid(EntityPlayerSP player, ClutchCandidate candidate) {
        return session.matches(mc.theWorld, player.getEntityId())
            && scanner.isValid(mc.theWorld, player, candidate, getReach(player),
                onlyPlaceSideways.isEnabled(), fov.getValue());
    }

    private boolean triggerMet(EntityPlayerSP player) {
        int x = MathHelper.floor_double(player.posX);
        int z = MathHelper.floor_double(player.posZ);
        int feetY = MathHelper.floor_double(player.posY);
        switch (trigger.getValue()) {
            case PREDICTED_DANGER:
                return predictor.isDangerPredicted(mc.theWorld, player, predictionTicks.getValue(),
                    minimumHeight.getValue());
            case ALWAYS:
                return true;
            case ON_VOID:
                return depthUntilSupport(x, feetY, z, 65) >= 65;
            case ON_LETHAL_FALL:
                return player.fallDistance + depthUntilSupport(x, feetY, z, 40) - 3.0F
                    >= player.getHealth() / 2.0F;
            case FALL_DISTANCE:
                return player.fallDistance + depthUntilSupport(x, feetY, z,
                    Math.max(1, (int) blocks.getValue()) + 1) >= blocks.getValue();
            default:
                return false;
        }
    }

    private int depthUntilSupport(int x, int feetY, int z, int limit) {
        int depth = 0;
        for (int offset = 1; offset <= limit; offset++) {
            triggerCursor.set(x, feetY - offset, z);
            Block block = mc.theWorld.getBlockState(triggerCursor).getBlock();
            if (block.getMaterial() != Material.air && !(block instanceof BlockLiquid)) break;
            depth++;
        }
        return depth;
    }

    private boolean conditionsMet(EntityPlayerSP player) {
        if (onlyMidAir.isEnabled() && player.onGround) return false;
        if (recentlyDamaged.isEnabled() && player.hurtTime <= 0) return false;
        if (movingBackwards.isEnabled()
                && (player.movementInput == null || player.movementInput.moveForward >= 0.0F)) return false;
        return predictor.countAirBelow(mc.theWorld, player, minimumHeight.getValue())
            >= minimumHeight.getValue();
    }

    private boolean ensureHoldingBlock(EntityPlayerSP player) {
        if (ClutchPlacementExecutor.isValidBlockStack(player.getHeldItem())) {
            slotSwitchPending = false;
            lastHeldBlockSlot = player.inventory.currentItem;
            return true;
        }
        if (selectBlocks.getValue() == SelectBlocksMode.NO) return false;
        if (selectBlocks.getValue() == SelectBlocksMode.ON_DEPLETION && lastHeldBlockSlot == -1) return false;
        if (slotSwitchPending) return false;
        int slot = findBlockSlot(player);
        if (slot < 0) return false;
        setSelectedSlot(slot);
        slotSwitchPending = true;
        return false;
    }

    private boolean hasBlocks(EntityPlayerSP player) {
        if (selectBlocks.getValue() == SelectBlocksMode.NO) {
            return ClutchPlacementExecutor.isValidBlockStack(player.getHeldItem());
        }
        return ClutchPlacementExecutor.isValidBlockStack(player.getHeldItem()) || findBlockSlot(player) >= 0;
    }

    private int findBlockSlot(EntityPlayerSP player) {
        for (int slot = 0; slot < 9; slot++) {
            if (ClutchPlacementExecutor.isValidBlockStack(player.inventory.getStackInSlot(slot))) return slot;
        }
        return -1;
    }

    private int heldBlockSlot(EntityPlayerSP player) {
        return ClutchPlacementExecutor.isValidBlockStack(player.getHeldItem())
            ? player.inventory.currentItem : -1;
    }

    private int countAvailableBlocks(EntityPlayerSP player) {
        int count = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (ClutchPlacementExecutor.isValidBlockStack(stack)) count += stack.stackSize;
        }
        return count;
    }

    private boolean acquireSlotLease(final EntityPlayerSP player) {
        if (slotLease != null) return slotLease.renew(2);
        final int original = player.inventory.currentItem;
        final boolean restore = returnToSlot.isEnabled();
        slotLease = getScope().acquire(ResourceArbiter.Resource.HOTBAR_SLOT, 400, 2, () -> {
            if (!restore || mc.thePlayer != player || original < 0 || original > 8) return;
            player.inventory.currentItem = original;
            if (mc.playerController != null) mc.playerController.syncCurrentPlayItem();
        });
        return slotLease != null;
    }

    private boolean acquireUseActionLease() {
        releaseUseActionLease();
        useActionLease = getScope().acquire(ResourceArbiter.Resource.USE_ACTION, 400, 1, null);
        return useActionLease != null;
    }

    private boolean acquireServerRotationLease() {
        if (serverRotationLease != null && serverRotationLease.renew(2)) return true;
        serverRotationLease = getScope().acquire(ResourceArbiter.Resource.SERVER_ROTATION, 95, 2, null);
        return serverRotationLease != null;
    }

    private void releaseUseActionLease() {
        ResourceArbiter.Lease lease = useActionLease;
        useActionLease = null;
        if (lease != null && lease.isValid()) lease.close();
    }

    private void setSelectedSlot(int slot) {
        if (!isPlayerReady() || mc.thePlayer.inventory.currentItem == slot) return;
        mc.thePlayer.inventory.currentItem = slot;
        if (mc.playerController != null) mc.playerController.updateController();
    }

    private void handleGroundState(EntityPlayerSP player, long now) {
        if (!clutching) return;
        groundedTicks++;
        if (groundedTicks < 2) return;
        session.transition(ClutchPhase.CLEANUP, "Saved", now);
        finishRecovery(player);
    }

    private void finishRecovery(EntityPlayerSP player) {
        releaseSlotLease();
        clutching = false;
        if (rotateBack.isEnabled() && !silentAim.isEnabled() && rotationActive) {
            returningToCamera = true;
            snapBackDelayTicks = snapBackDelay.getValue();
            snapBackDurationTicks = snapBackDuration.getValue();
            setRotationTarget(savedCameraYaw, savedCameraPitch);
            return;
        }
        boolean disable = disableAfterwards.isEnabled();
        resetState();
        clearRotationState();
        if (disable) setEnabled(false);
    }

    private void handleSnapBack(EntityPlayerSP player) {
        if (keepJumpDirection.isEnabled() && !player.onGround && player.motionY > 0.0D) {
            snapBackDelayTicks = 0;
            snapBackDurationTicks = 0;
            currentYaw = savedCameraYaw;
            currentPitch = savedCameraPitch;
        }
        if (snapBackDelayTicks > 0) {
            snapBackDelayTicks--;
            return;
        }
        stepRotation(resolveSnapBackSpeed());
        if (snapBackDurationTicks > 0) snapBackDurationTicks--;
        applyVisibleRotation(player);
        if (hasReachedTarget(2.0F)) {
            boolean disable = disableAfterwards.isEnabled();
            resetState();
            clearRotationState();
            if (disable) setEnabled(false);
        }
    }

    private void setRotationTarget(float yaw, float pitch) {
        if (!rotationActive && mc.thePlayer != null) {
            currentYaw = mc.thePlayer.rotationYaw;
            currentPitch = mc.thePlayer.rotationPitch;
        }
        targetYaw = yaw;
        targetPitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        rotationActive = true;
    }

    private void stepRotation(float maxDegrees) {
        if (!rotationActive || maxDegrees <= 0.0F || mc.gameSettings == null) return;
        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(targetPitch - currentPitch);
        float distance = MathHelper.sqrt_float(yawDiff * yawDiff + pitchDiff * pitchDiff);
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 1.2F;
        float ratio = distance <= maxDegrees ? 1.0F : maxDegrees / Math.max(distance, 0.001F);
        currentYaw += Math.round(yawDiff * ratio / gcd) * gcd;
        currentPitch = MathHelper.clamp_float(currentPitch + Math.round(pitchDiff * ratio / gcd) * gcd,
            -90.0F, 90.0F);
    }

    private float resolveAimSpeed() {
        float base = (float) rotationSpeed.getValue();
        if (aimAcceleration.getValue() > 0 && rotationActive) {
            float yaw = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
            float pitch = MathHelper.wrapAngleTo180_float(targetPitch - currentPitch);
            float distance = MathHelper.sqrt_float(yaw * yaw + pitch * pitch);
            float accelerated = Math.max(base,
                aimAcceleration.getValue() * Math.min(1.0F, distance / 90.0F));
            float strength = accelerationStrength.getValue() / 100.0F;
            base = base * (1.0F - strength) + accelerated * strength;
        }
        if (randomization.getValue() <= 0) return base;
        double spread = 0.18D * randomization.getValue() / 100.0D;
        return Math.max(1.0F, (float) (base * (1.0D
            + (random.nextDouble() * 2.0D - 1.0D) * spread)));
    }

    private float resolveSnapBackSpeed() {
        if (snapBackDuration.getValue() <= 0 && snapBackDurationTicks <= 0) {
            return (float) rotationSpeed.getValue();
        }
        int ticks = Math.max(1, snapBackDurationTicks);
        float yaw = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - currentYaw));
        float pitch = Math.abs(MathHelper.wrapAngleTo180_float(targetPitch - currentPitch));
        return Math.max(1.0F, MathHelper.sqrt_float(yaw * yaw + pitch * pitch) / ticks);
    }

    private boolean hasReachedTarget(float tolerance) {
        return !rotationActive
            || (Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - currentYaw)) <= tolerance
                && Math.abs(targetPitch - currentPitch) <= tolerance);
    }

    private void applyVisibleRotation(EntityPlayerSP player) {
        if (player == null || silentAim.isEnabled() || !rotationActive) return;
        player.rotationYaw = currentYaw;
        player.rotationPitch = currentPitch;
    }

    private MovingObjectPosition rayTraceAtRotation(EntityPlayerSP player, double reach, float yaw, float pitch) {
        float originalYaw = player.rotationYaw;
        float originalPitch = player.rotationPitch;
        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        try {
            return player.rayTrace(reach, 1.0F);
        } finally {
            player.rotationYaw = originalYaw;
            player.rotationPitch = originalPitch;
        }
    }

    private static boolean matchesCandidate(MovingObjectPosition hit, ClutchCandidate candidate) {
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && candidate.getNeighbor().equals(hit.getBlockPos()) && candidate.getFace() == hit.sideHit;
    }

    private boolean canPlaceNow(long now) {
        return lastAttemptNanos <= 0L || now - lastAttemptNanos >= nextAttemptDelayNanos;
    }

    private boolean canRetryCandidate(ClutchCandidate candidate, long now) {
        if (lastAttemptNeighbor == null || lastAttemptFace == null
                || !lastAttemptNeighbor.equals(candidate.getNeighbor())
                || lastAttemptFace != candidate.getFace()) return true;
        return now - lastAttemptNanos >= Math.max(50_000_000L, nextAttemptDelayNanos);
    }

    private void recordAttempt(ClutchCandidate candidate, long now) {
        lastAttemptNanos = now;
        double delay = 1_000_000_000.0D / Math.max(1, clickSpeed.getValue());
        if (randomization.getValue() > 0) {
            double spread = 0.30D * randomization.getValue() / 100.0D;
            delay *= 1.0D + (random.nextDouble() * 2.0D - 1.0D) * spread;
        }
        nextAttemptDelayNanos = Math.max(25_000_000L, (long) delay);
        lastAttemptNeighbor = candidate.getNeighbor();
        lastAttemptFace = candidate.getFace();
    }

    private double getReach(EntityPlayerSP player) {
        return player.capabilities.isCreativeMode ? 5.0D : 4.5D;
    }

    private void abortClutch() {
        releaseUseActionLease();
        releaseSlotLease();
        resetState();
        clearRotationState();
    }

    private void releaseSlotLease() {
        ResourceArbiter.Lease lease = slotLease;
        slotLease = null;
        if (lease != null && lease.isValid()) lease.close();
    }

    private void clearRotationState() {
        ResourceArbiter.Lease lease = serverRotationLease;
        serverRotationLease = null;
        if (lease != null && lease.isValid()) lease.close();
        silentRotation.clear();
        rotationActive = false;
        rotationHeldTicks = 0;
        ClientRotationHelper helper = ClientRotationHelper.get();
        if ("Clutch".equals(helper.getRequestedOwner())) helper.clearRequestedRotations();
    }

    private void resetState() {
        releaseUseActionLease();
        confirmation.clear();
        bridge.clear();
        session.reset();
        activeCandidate = null;
        clutching = false;
        returningToCamera = false;
        slotSwitchPending = false;
        lastHeldBlockSlot = -1;
        groundedTicks = 0;
        rotationHeldTicks = 0;
        snapBackDelayTicks = 0;
        snapBackDurationTicks = 0;
        lastAttemptNanos = 0L;
        nextAttemptDelayNanos = 0L;
        lastAttemptNeighbor = null;
        lastAttemptFace = null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        if (!isEnabled() || !silentAim.isEnabled() || !rotationActive || !isPlayerReady()
                || serverRotationLease == null || !serverRotationLease.isValid()) return;
        ClientRotationHelper helper = ClientRotationHelper.get();
        if (helper.requestRotations("Clutch", 95, currentYaw, currentPitch)
                || "Clutch".equals(helper.getRequestedOwner())) {
            event.yaw = Float.valueOf(currentYaw);
            event.pitch = Float.valueOf(currentPitch);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onForgeRenderPre(Event event) {
        if (event != null && "net.minecraftforge.client.event.RenderPlayerEvent$Pre".equals(event.getClass().getName())) {
            silentRotation.renderPre(event, mc.thePlayer, isEnabled() && silentAim.isEnabled());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onForgeRenderPost(Event event) {
        if (event != null && "net.minecraftforge.client.event.RenderPlayerEvent$Post".equals(event.getClass().getName())) {
            silentRotation.renderPost(event, mc.thePlayer);
        }
    }

    @Override
    public String getHudInfo() {
        return session.getPhase() == ClutchPhase.BRIDGING ? bridge.status()
            : session.getDetail() + (session.getBlocksPlaced() > 0
                ? " " + session.getBlocksPlaced() + "/" + maxBlocks.getValue() : "");
    }

    private boolean isPlayerReady() {
        return mc.thePlayer != null && mc.theWorld != null && !mc.thePlayer.isDead;
    }

    private void registerForge() {
        if (forgeRegistered) return;
        MinecraftForge.EVENT_BUS.register(this);
        forgeRegistered = true;
    }

    private void unregisterForge() {
        if (!forgeRegistered) return;
        MinecraftForge.EVENT_BUS.unregister(this);
        forgeRegistered = false;
    }

    public enum Trigger {
        PREDICTED_DANGER("Predicted Danger"),
        ALWAYS,
        ON_VOID,
        ON_LETHAL_FALL,
        FALL_DISTANCE;

        private final String displayName;
        Trigger() { displayName = null; }
        Trigger(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName == null ? name() : displayName; }
    }

    public enum RecoveryMode {
        EMERGENCY_ONLY("Emergency Only"),
        EMERGENCY_BRIDGE("Emergency + Bridge");

        private final String displayName;
        RecoveryMode(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    public enum FilterMode {
        NONE,
        BLACKLIST,
        WHITELIST
    }

    public enum SelectBlocksMode {
        NO("No"),
        ON_DEPLETION("On Depletion"),
        ALWAYS("Always");

        private final String displayName;
        SelectBlocksMode(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }
}
