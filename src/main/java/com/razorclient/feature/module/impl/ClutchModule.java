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
import com.razorclient.feature.module.impl.clutch.DangerPrediction;
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
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
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
    private final BooleanSetting tellyAssist = new BooleanSetting("Telly Assist", false);
    private final DecimalSetting tellyCps = new DecimalSetting("Telly CPS", 15.0D, 25.0D, 0.5D, 20.0D);
    private final NumberSetting tellyFlickSpeed = new NumberSetting("Telly Flick Speed", 2, 5, 1, 3);
    private final DecimalSetting tellyOvershoot = new DecimalSetting("Telly Overshoot", 0.0D, 5.0D, 0.25D, 2.0D);

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
    private ResourceArbiter.Lease forwardInputLease;
    private ResourceArbiter.Lease jumpInputLease;
    private ClutchCandidate activeCandidate;
    private DangerPrediction activePrediction = DangerPrediction.safe();
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
    private TellyPhase tellyPhase = TellyPhase.RUNNING;
    private boolean tellyActive;
    private boolean tellyForwardWasDown;
    private boolean tellyJumpWasDown;
    private boolean tellyWarnedNoBlocks;
    private Object tellyWorld;
    private int tellyPlayerId = -1;
    private int tellyOriginalSlot = -1;
    private int tellyAirTicks;
    private int tellyPhaseTicks;
    private int tellyPlacements;
    private int tellyPreferredSlot;
    private long tellyNextPlaceNanos;
    private long tellyNextSwapNanos;
    private float tellyOriginalYaw;
    private float tellyOriginalPitch;
    private float tellyPhaseStartYaw;
    private float tellyPhaseStartPitch;
    private float tellyBackwardYaw;
    private float tellyBackwardPitch;
    private float tellyOvershootAmount;
    private ClutchCandidate tellyPendingCandidate;
    private Object tellyPendingWorld;
    private int tellyPendingSlot = -1;
    private int tellyPendingStackCount;
    private int tellyConfirmationAge;

    public ClutchModule() {
        super("Clutch", "Places emergency blocks and builds a short recovery path", Category.PLAYER,
            Keyboard.KEY_NONE);
        blocks.setVisibility(() -> trigger.getValue() == Trigger.FALL_DISTANCE);
        rotateBack.setVisibility(() -> !silentAim.isEnabled());
        clutchMoveDelay.setVisibility(() -> false);
        filterMode.setVisibility(() -> false);
        tellyCps.setVisibility(tellyAssist::isEnabled);
        tellyFlickSpeed.setVisibility(tellyAssist::isEnabled);
        tellyOvershoot.setVisibility(tellyAssist::isEnabled);
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
        addSetting(tellyAssist);
        addSetting(tellyCps);
        addSetting(tellyFlickSpeed);
        addSetting(tellyOvershoot);
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
        if (returningToCamera) {
            handleSnapBack(player);
            return;
        }
        if (tellyActive && !matchesTellySession(player)) {
            abortTelly();
        }
        if (tellyActive && !player.onGround && player.motionY < 0.0D
                && triggerMet(player) && conditionsMet(player)) {
            abortTelly();
        }
        if (!clutching && tellyAssist.isEnabled()) {
            if (tellyActive || tryBeginTelly(player, now)) {
                if (processTelly(player, now)) return;
            }
        } else if (tellyActive) {
            abortTelly();
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
                activeCandidate = scanner.randomizeAim(mc.theWorld, player, activeCandidate,
                    random, randomization.getValue());
                session.clearBlocked(ClutchSession.BlockedReason.NO_FACE);
            }
        }
        if (activeCandidate == null) {
            if (session.markBlocked(ClutchSession.BlockedReason.NO_FACE, "No Face", now)) abortClutch();
            return;
        }
        setRotationTarget(activeCandidate.yawFrom(player), activeCandidate.pitchFrom(player));
        stepRotation(resolveAimSpeed());
        boolean rotationGranted = silentAim.isEnabled()
            ? silentRotation.update(getScope(), player, currentYaw, currentPitch)
            : ClientRotationHelper.get().requestRotations("Clutch", 95, currentYaw, currentPitch);
        if (!rotationGranted) {
            if (session.markBlocked(ClutchSession.BlockedReason.SUPPRESSED,
                    "Suppressed", now)) abortClutch();
            return;
        }
        session.clearBlocked(ClutchSession.BlockedReason.SUPPRESSED);
        if (!silentAim.isEnabled()) {
            silentRotation.clear();
            applyVisibleRotation(player);
        }
        session.transition(session.getPhase() == ClutchPhase.BRIDGING ? ClutchPhase.BRIDGING : ClutchPhase.AIMING,
            session.getPhase() == ClutchPhase.BRIDGING ? bridge.status() : "Aiming", now);
        if (!hasReachedTarget(2.0F)) {
            rotationHeldTicks = 0;
            return;
        }
        int requiredAlignmentTicks = requiresImmediatePlacement(player) ? 1 : 2;
        rotationHeldTicks = Math.min(requiredAlignmentTicks, rotationHeldTicks + 1);
        if (rotationHeldTicks < requiredAlignmentTicks || !canPlaceNow(now)
                || !canRetryCandidate(activeCandidate, now)) return;

        MovingObjectPosition hit = rayTraceAtRotation(player, getReach(player), currentYaw, currentPitch);
        if (!matchesCandidate(hit, activeCandidate)) {
            activeCandidate = null;
            rotationHeldTicks = 0;
            if (session.markBlocked(ClutchSession.BlockedReason.NO_FACE, "Replanning", now)) abortClutch();
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
            if (trigger.getValue() == Trigger.PREDICTED_DANGER) {
                DangerPrediction refreshed = predictor.predict(mc.theWorld, player,
                    predictionTicks.getValue(), minimumHeight.getValue());
                if (refreshed.isDangerous()) activePrediction = refreshed;
            }
            return scanner.findEmergency(mc.theWorld, player, activePrediction, range.getValue(),
                session.getBlocksPlaced(), getReach(player), onlyPlaceSideways.isEnabled(),
                fov.getValue(), multipoint.isEnabled());
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
                activePrediction = predictor.predict(mc.theWorld, player, predictionTicks.getValue(),
                    minimumHeight.getValue());
                return activePrediction.isDangerous();
            case ALWAYS:
                activePrediction = DangerPrediction.safe();
                return true;
            case ON_VOID:
                activePrediction = DangerPrediction.safe();
                return depthUntilSupport(x, feetY, z, 65) >= 65;
            case ON_LETHAL_FALL:
                activePrediction = DangerPrediction.safe();
                return player.fallDistance + depthUntilSupport(x, feetY, z, 40) - 3.0F
                    >= player.getHealth() / 2.0F;
            case FALL_DISTANCE:
                activePrediction = DangerPrediction.safe();
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
        return trigger.getValue() == Trigger.PREDICTED_DANGER
            || predictor.countAirBelow(mc.theWorld, player, minimumHeight.getValue())
                >= minimumHeight.getValue();
    }

    private boolean requiresImmediatePlacement(EntityPlayerSP player) {
        return (activePrediction != null && activePrediction.isDangerous())
            || player.fallDistance >= 0.75F || player.motionY <= -0.22D;
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

    private boolean tryBeginTelly(EntityPlayerSP player, long now) {
        if (!player.onGround || mc.currentScreen != null || !mc.inGameHasFocus
                || !isPhysicalKeyDown(mc.gameSettings.keyBindForward.getKeyCode())
                || !isPhysicalKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) return false;
        int blockSlot = findTellyBlockSlot(player, 2);
        if (blockSlot < 0) blockSlot = findTellyBlockSlot(player, 3);
        if (blockSlot < 0) blockSlot = findBlockSlot(player);
        if (blockSlot < 0) {
            warnNoTellyBlocks(player);
            return false;
        }
        tellyForwardWasDown = true;
        tellyJumpWasDown = true;
        if (!acquireTellyInputLeases(player)) return false;
        if (!acquireSlotLease(player)) {
            releaseTellyInputLeases();
            return false;
        }
        tellyActive = true;
        tellyWorld = mc.theWorld;
        tellyPlayerId = player.getEntityId();
        tellyOriginalSlot = player.inventory.currentItem;
        tellyOriginalYaw = player.rotationYaw;
        tellyOriginalPitch = player.rotationPitch;
        tellyPhaseStartYaw = player.rotationYaw;
        tellyPhaseStartPitch = player.rotationPitch;
        tellyBackwardYaw = player.rotationYaw + 180.0F + randomBetween(-2.0F, 2.0F);
        tellyBackwardPitch = randomBetween(25.0F, 45.0F);
        tellyOvershootAmount = (random.nextBoolean() ? 1.0F : -1.0F) * (float) tellyOvershoot.getValue();
        tellyAirTicks = 0;
        tellyPhaseTicks = 0;
        tellyPlacements = 0;
        tellyPreferredSlot = blockSlot == 3 ? 3 : 2;
        tellyNextPlaceNanos = now;
        tellyNextSwapNanos = now;
        tellyWarnedNoBlocks = false;
        tellyPhase = TellyPhase.RUNNING;
        return true;
    }

    /** Returns true while Telly owns this tick; false hands control to recovery. */
    private boolean processTelly(EntityPlayerSP player, long now) {
        if (!tellyActive) return false;
        if (mc.currentScreen != null || !mc.inGameHasFocus || player.isDead) {
            abortTelly();
            return true;
        }
        if (!renewTellyInputLeases()) {
            abortTelly();
            return true;
        }
        setTellyInputState(true, true);
        if (!player.onGround) tellyAirTicks++;

        switch (tellyPhase) {
            case RUNNING:
                tellyPhase = TellyPhase.FLICKING_BACK;
                tellyPhaseTicks = 0;
                return true;
            case FLICKING_BACK:
                if (!applyTellyFlick(player, tellyBackwardYaw, tellyBackwardPitch, true)) {
                    abortTelly();
                    return true;
                }
                if (++tellyPhaseTicks >= tellyFlickSpeed.getValue()) {
                    tellyPhase = TellyPhase.PLACING;
                    tellyPhaseTicks = 0;
                }
                return true;
            case PLACING:
                TellyConfirmationResult confirmationResult = pollTellyConfirmation(player);
                if (confirmationResult == TellyConfirmationResult.PENDING) return true;
                if (confirmationResult == TellyConfirmationResult.CONFIRMED) {
                    tellyPlacements++;
                    tellyPreferredSlot = tellyPendingSlot == 2 ? 3 : 2;
                    clearTellyConfirmation();
                } else if (confirmationResult == TellyConfirmationResult.FAILED) {
                    clearTellyConfirmation();
                    if (!predictor.hasCollisionSupport(mc.theWorld, player, player.getEntityBoundingBox())) {
                        abortTelly();
                        return false;
                    }
                }
                if (player.onGround && tellyAirTicks > 0 || tellyAirTicks > 4) {
                    beginTellyReturn(player);
                    return true;
                }
                if (tellyAirTicks < 1) return true;
                if (now < tellyNextPlaceNanos) return true;
                ClutchCandidate candidate = createTellyCandidate(player);
                if (candidate == null) {
                    if (!predictor.hasCollisionSupport(mc.theWorld, player, player.getEntityBoundingBox())) {
                        abortTelly();
                        return false;
                    }
                    beginTellyReturn(player);
                    return true;
                }
                int slot = chooseTellySlot(player, now);
                if (slot < 0) {
                    warnNoTellyBlocks(player);
                    abortTelly();
                    return true;
                }
                setSelectedSlot(slot);
                if (!acquireUseActionLease()) return true;
                MovingObjectPosition hit = tellyHit(candidate);
                ItemStack stackBeforeAttempt = player.inventory.getStackInSlot(slot);
                int countBeforeAttempt = stackBeforeAttempt == null ? 0 : stackBeforeAttempt.stackSize;
                boolean invoked;
                try {
                    invoked = placementExecutor.attempt(mc, player, hit, candidate);
                } finally {
                    releaseUseActionLease();
                }
                tellyNextPlaceNanos = now + (long) (1_000_000_000.0D / tellyCps.getValue());
                if (invoked) {
                    beginTellyConfirmation(candidate, slot, countBeforeAttempt);
                } else if (!predictor.hasCollisionSupport(mc.theWorld, player, player.getEntityBoundingBox())) {
                    abortTelly();
                    return false;
                }
                return true;
            case FLICKING_FORWARD:
                if (!applyTellyFlick(player, tellyOriginalYaw, tellyOriginalPitch, false)) {
                    abortTelly();
                    return true;
                }
                if (++tellyPhaseTicks >= tellyFlickSpeed.getValue()) abortTelly();
                return true;
            default:
                return true;
        }
    }

    private void beginTellyReturn(EntityPlayerSP player) {
        tellyPhase = TellyPhase.FLICKING_FORWARD;
        tellyPhaseTicks = 0;
        tellyPhaseStartYaw = player.rotationYaw;
        tellyPhaseStartPitch = player.rotationPitch;
    }

    private boolean applyTellyFlick(EntityPlayerSP player, float endYaw, float endPitch, boolean overshoot) {
        int duration = Math.max(1, tellyFlickSpeed.getValue());
        float progress = Math.min(1.0F, (tellyPhaseTicks + 1.0F) / duration);
        float eased;
        float extraYaw = 0.0F;
        if (overshoot && tellyPhaseTicks + 1 < duration) {
            float approach = Math.min(1.0F, (tellyPhaseTicks + 1.0F) / Math.max(1.0F, duration - 1.0F));
            eased = easeInOutCubic(approach);
            extraYaw = tellyOvershootAmount * eased;
        } else {
            eased = easeInOutCubic(progress);
        }
        float yawDelta = MathHelper.wrapAngleTo180_float(endYaw - tellyPhaseStartYaw);
        float yaw = tellyPhaseStartYaw + yawDelta * eased + extraYaw;
        float pitch = tellyPhaseStartPitch + (endPitch - tellyPhaseStartPitch) * eased;
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 1.2F;
        yaw = player.rotationYaw + Math.round(MathHelper.wrapAngleTo180_float(yaw - player.rotationYaw) / gcd) * gcd;
        pitch = MathHelper.clamp_float(player.rotationPitch
            + Math.round((pitch - player.rotationPitch) / gcd) * gcd, -90.0F, 90.0F);
        currentYaw = yaw;
        currentPitch = pitch;
        rotationActive = true;
        if (!ClientRotationHelper.get().requestRotations("Clutch", 95, yaw, pitch)) return false;
        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        player.prevRotationYawHead = player.rotationYawHead;
        player.prevRenderYawOffset = player.renderYawOffset;
        player.rotationYawHead = yaw;
        player.renderYawOffset = yaw;
        return true;
    }

    private static float easeInOutCubic(float progress) {
        return progress < 0.5F ? 4.0F * progress * progress * progress
            : 1.0F - (float) Math.pow(-2.0F * progress + 2.0F, 3.0D) / 2.0F;
    }

    private void beginTellyConfirmation(ClutchCandidate candidate, int slot, int stackCount) {
        tellyPendingCandidate = candidate;
        tellyPendingWorld = mc.theWorld;
        tellyPendingSlot = slot;
        tellyPendingStackCount = stackCount;
        tellyConfirmationAge = 0;
    }

    private TellyConfirmationResult pollTellyConfirmation(EntityPlayerSP player) {
        if (tellyPendingCandidate == null) return TellyConfirmationResult.NONE;
        if (tellyPendingWorld != mc.theWorld || player == null) return TellyConfirmationResult.FAILED;
        BlockPos target = tellyPendingCandidate.getTargetPos();
        Block targetBlock = mc.theWorld.getBlockState(target).getBlock();
        if (!targetBlock.isReplaceable(mc.theWorld, target)) return TellyConfirmationResult.CONFIRMED;
        ItemStack stack = tellyPendingSlot < 0 ? null : player.inventory.getStackInSlot(tellyPendingSlot);
        int currentCount = stack == null ? 0 : stack.stackSize;
        if (currentCount < tellyPendingStackCount) return TellyConfirmationResult.CONFIRMED;
        tellyConfirmationAge++;
        return tellyConfirmationAge <= confirmationTicks.getValue()
            ? TellyConfirmationResult.PENDING : TellyConfirmationResult.FAILED;
    }

    private void clearTellyConfirmation() {
        tellyPendingCandidate = null;
        tellyPendingWorld = null;
        tellyPendingSlot = -1;
        tellyPendingStackCount = 0;
        tellyConfirmationAge = 0;
    }

    private ClutchCandidate createTellyCandidate(EntityPlayerSP player) {
        Float publishedYaw = ClientRotationHelper.get().getServerYaw();
        float yaw = publishedYaw == null ? player.rotationYaw : publishedYaw.floatValue();
        double radians = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(radians));
        int dz = (int) Math.round(Math.cos(radians));
        if (dx == 0 && dz == 0) dz = 1;
        BlockPos feet = new BlockPos(player.posX, player.posY - 1.0D, player.posZ);
        BlockPos target = feet.add(dx, 0, dz);
        Block targetBlock = mc.theWorld.getBlockState(target).getBlock();
        if (!targetBlock.isReplaceable(mc.theWorld, target)) return null;
        BlockPos support = target.down();
        Block supportBlock = mc.theWorld.getBlockState(support).getBlock();
        Material material = supportBlock.getMaterial();
        if (!material.isSolid() || supportBlock instanceof BlockLiquid
                || supportBlock.isReplaceable(mc.theWorld, support)) return null;
        double hitX = support.getX() + 0.5D;
        double hitY = support.getY() + 1.0D;
        double hitZ = support.getZ() + 0.5D;
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        double reach = getReach(player);
        double distanceSq = square(hitX - eyeX) + square(hitY - eyeY) + square(hitZ - eyeZ);
        if (distanceSq > reach * reach) return null;
        return new ClutchCandidate(target, support, EnumFacing.UP, hitX, hitY, hitZ, 0.0D);
    }

    private static MovingObjectPosition tellyHit(ClutchCandidate candidate) {
        return new MovingObjectPosition(new Vec3(candidate.getHitX(), candidate.getHitY(), candidate.getHitZ()),
            candidate.getFace(), candidate.getNeighbor());
    }

    private int chooseTellySlot(EntityPlayerSP player, long now) {
        if (now < tellyNextSwapNanos
                && ClutchPlacementExecutor.isValidBlockStack(player.getHeldItem())) {
            return player.inventory.currentItem;
        }
        int preferred = tellyPreferredSlot;
        int alternate = preferred == 2 ? 3 : 2;
        int selected = findTellyBlockSlot(player, preferred);
        if (selected < 0) selected = findTellyBlockSlot(player, alternate);
        if (selected < 0) selected = findBlockSlot(player);
        if (selected < 0) return -1;
        if (now >= tellyNextSwapNanos && selected != player.inventory.currentItem) {
            tellyNextSwapNanos = now + 20_000_000L + (long) (random.nextDouble() * 30_000_000L);
        }
        return selected;
    }

    private static int findTellyBlockSlot(EntityPlayerSP player, int slot) {
        return slot >= 0 && slot < 9
            && ClutchPlacementExecutor.isValidBlockStack(player.inventory.getStackInSlot(slot)) ? slot : -1;
    }

    private boolean acquireTellyInputLeases(final EntityPlayerSP player) {
        forwardInputLease = getScope().acquire(ResourceArbiter.Resource.FORWARD_INPUT, 400, 2,
            () -> restoreKey(mc.gameSettings.keyBindForward, tellyForwardWasDown));
        if (forwardInputLease == null) return false;
        jumpInputLease = getScope().acquire(ResourceArbiter.Resource.JUMP_INPUT, 400, 2,
            () -> restoreKey(mc.gameSettings.keyBindJump, tellyJumpWasDown));
        if (jumpInputLease != null) return true;
        forwardInputLease.close();
        forwardInputLease = null;
        return false;
    }

    private boolean renewTellyInputLeases() {
        return forwardInputLease != null && jumpInputLease != null
            && forwardInputLease.renew(2) && jumpInputLease.renew(2);
    }

    private void releaseTellyInputLeases() {
        ResourceArbiter.Lease forward = forwardInputLease;
        ResourceArbiter.Lease jump = jumpInputLease;
        forwardInputLease = null;
        jumpInputLease = null;
        if (forward != null && forward.isValid()) forward.close();
        if (jump != null && jump.isValid()) jump.close();
    }

    private void setTellyInputState(boolean forward, boolean jump) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), forward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), jump);
    }

    private static void restoreKey(KeyBinding binding, boolean down) {
        if (binding != null) KeyBinding.setKeyBindState(binding.getKeyCode(), down);
    }

    private static boolean isPhysicalKeyDown(int keyCode) {
        return keyCode > 0 && Keyboard.isKeyDown(keyCode);
    }

    private boolean matchesTellySession(EntityPlayerSP player) {
        return tellyWorld == mc.theWorld && tellyPlayerId == player.getEntityId();
    }

    private void abortTelly() {
        if (!tellyActive && forwardInputLease == null && jumpInputLease == null) return;
        EntityPlayerSP player = mc.thePlayer;
        releaseUseActionLease();
        releaseTellyInputLeases();
        if (player != null && tellyOriginalSlot >= 0 && tellyOriginalSlot < 9) {
            setSelectedSlot(tellyOriginalSlot);
        }
        releaseSlotLease();
        if (player != null && matchesTellySession(player)) {
            player.rotationYaw = tellyOriginalYaw;
            player.rotationPitch = tellyOriginalPitch;
            player.prevRotationYawHead = player.rotationYawHead;
            player.prevRenderYawOffset = player.renderYawOffset;
            player.rotationYawHead = tellyOriginalYaw;
            player.renderYawOffset = tellyOriginalYaw;
        }
        tellyActive = false;
        tellyPhase = TellyPhase.RUNNING;
        tellyWorld = null;
        tellyPlayerId = -1;
        tellyOriginalSlot = -1;
        tellyAirTicks = 0;
        tellyPhaseTicks = 0;
        tellyPlacements = 0;
        tellyNextPlaceNanos = 0L;
        tellyNextSwapNanos = 0L;
        clearTellyConfirmation();
        clearRotationState();
    }

    private void warnNoTellyBlocks(EntityPlayerSP player) {
        if (tellyWarnedNoBlocks || player == null) return;
        tellyWarnedNoBlocks = true;
        player.addChatMessage(new ChatComponentText("\u00a7c[Clutch] Telly Assist needs full-cube blocks."));
    }

    private float randomBetween(float minimum, float maximum) {
        return minimum + random.nextFloat() * (maximum - minimum);
    }

    private static double square(double value) {
        return value * value;
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
        abortTelly();
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
        silentRotation.clear();
        rotationActive = false;
        rotationHeldTicks = 0;
        ClientRotationHelper helper = ClientRotationHelper.get();
        if ("Clutch".equals(helper.getRequestedOwner())) helper.clearRequestedRotations();
    }

    private void resetState() {
        releaseTellyInputLeases();
        releaseUseActionLease();
        confirmation.clear();
        bridge.clear();
        session.reset();
        activeCandidate = null;
        activePrediction = DangerPrediction.safe();
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
        tellyWarnedNoBlocks = false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        if (!isEnabled() || !silentAim.isEnabled() || !rotationActive || !isPlayerReady()
                || event == null) return;
        ClientRotationHelper helper = ClientRotationHelper.get();
        if ("Clutch".equals(helper.getRequestedOwner())) {
            event.yaw = Float.valueOf(currentYaw);
            event.pitch = Float.valueOf(currentPitch);
        }
    }

    @Override
    public String getHudInfo() {
        if (tellyActive) {
            switch (tellyPhase) {
                case FLICKING_BACK: return "Telly Flick";
                case PLACING: return "Telly Place " + tellyPlacements + "/4";
                case FLICKING_FORWARD: return "Telly Return";
                case RUNNING:
                default: return "Telly Run";
            }
        }
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

    private enum TellyPhase {
        RUNNING,
        FLICKING_BACK,
        PLACING,
        FLICKING_FORWARD
    }

    private enum TellyConfirmationResult {
        NONE,
        PENDING,
        CONFIRMED,
        FAILED
    }
}
