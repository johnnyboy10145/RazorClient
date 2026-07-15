package com.razorclient.feature.module.impl;

import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.event.ClientRotationEvent;
import com.razorclient.event.PrePlayerInputEvent;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.runtime.ResourceArbiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Keyboard;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class ClutchModule extends Module {
    private static final EnumFacing[] SEARCH_DIRECTIONS = new EnumFacing[] {
        EnumFacing.NORTH,
        EnumFacing.SOUTH,
        EnumFacing.EAST,
        EnumFacing.WEST
    };
    private static final EnumFacing[] PLACEMENT_FACE_PRIORITY = new EnumFacing[] {
        EnumFacing.DOWN,
        EnumFacing.NORTH,
        EnumFacing.SOUTH,
        EnumFacing.EAST,
        EnumFacing.WEST,
        EnumFacing.UP
    };

    private final Minecraft mc = Minecraft.getMinecraft();

    private final EnumSetting<Trigger> trigger = new EnumSetting<Trigger>("Trigger", Trigger.values(), Trigger.ALWAYS);
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
    private final Random random = getScope().getRandom();

    private int blocksPlaced;
    private int savedSlot = -1;
    private ResourceArbiter.Lease slotLease;
    private int moveFreezeTicks;
    private int groundedClutchTicks;
    private int snapBackDelayTicks;
    private int snapBackDurationTicks;
    private boolean clutching;
    private boolean returningToCamera;
    private float savedCamYaw;
    private float savedCamPitch;
    private float currentYaw;
    private float currentPitch;
    private float targetYaw;
    private float targetPitch;
    private boolean rotationActive;
    private int rotationHeldTicks;
    private List<BlockPos> bridgePath;
    private int bridgeIndex;
    private PlacementCandidate bridgeStartPlacement;
    private boolean slotSwitchPending;
    private boolean forgeRegistered;
    private long lastPlacementAttemptNanos;
    private long nextPlacementDelayNanos;
    private BlockPos lastPlacementTarget;
    private EnumFacing lastPlacementFace;
    private int lastHeldBlockSlot = -1;
    private boolean modelRotationActive;
    private ResourceArbiter.Lease modelRotationLease;
    private boolean modelRenderSwapActive;
    private EntityPlayerSP modelRenderPlayer;
    private float savedRenderYawHead;
    private float savedPrevRenderYawHead;
    private float savedRenderYawOffset;
    private float savedPrevRenderYawOffset;
    private float savedRenderPitch;
    private float savedPrevRenderPitch;
    private float previousSilentYaw;
    private float lastSilentYaw;
    private float previousSilentPitch;
    private float lastSilentPitch;
    private java.lang.reflect.Field renderPlayerField;

    public ClutchModule() {
        super("Clutch", "Bridges blocks back to safety when knocked off an edge", Category.PLAYER, Keyboard.KEY_NONE);

        blocks.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return trigger.getValue() == Trigger.FALL_DISTANCE;
            }
        });
        rotateBack.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return !silentAim.isEnabled();
            }
        });

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
    }

    @Override
    protected void onEnable() {
        resetState();
        registerForge();
    }

    @Override
    protected void onDisable() {
        unregisterForge();
        restoreSavedSlot(mc.thePlayer);
        resetState();
        clearRotationState();
    }

    @Override
    public void onSessionReset() {
        restoreSavedSlot(mc.thePlayer);
        resetState();
        clearRotationState();
    }

    @Override
    public void onInputContextLost() {
        restoreSavedSlot(mc.thePlayer);
        resetState();
        clearRotationState();
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        // Recover immediately if a renderer abort skipped the matching Post event.
        restoreModelRenderSwap();

        if (!isPlayerReady()) {
            resetState();
            clearRotationState();
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (slotLease != null && !slotLease.renew(2)) {
            slotLease = null;
            abortClutch(player);
            return;
        }
        if (moveFreezeTicks > 0) {
            moveFreezeTicks--;
            return;
        }

        if (returningToCamera) {
            handleSnapBack(player);
            return;
        }

        if (player.onGround) {
            handleGroundState(player);
            return;
        }
        groundedClutchTicks = 0;

        if (player.motionY >= 0.0D) {
            abortClutch(player);
            return;
        }
        if (!triggerMet(player)) {
            abortActiveClutch(player);
            return;
        }
        if (!conditionsMet(player)) {
            abortActiveClutch(player);
            return;
        }
        if (!hasBlocks(player)) {
            abortActiveClutch(player);
            return;
        }

        if (!clutching) {
            if (!acquireSlotLease(player)) return;
            clutching = true;
            blocksPlaced = 0;
            returningToCamera = false;
            rotationHeldTicks = 0;
            bridgeStartPlacement = null;
            nextPlacementDelayNanos = 0L;
            lastHeldBlockSlot = heldBlockSlot(player);
            refreshBridgePlan(player);
            savedSlot = returnToSlot.isEnabled() ? player.inventory.currentItem : -1;
            savedCamYaw = player.rotationYaw;
            savedCamPitch = player.rotationPitch;
        }

        if (blocksPlaced >= maxBlocks.getValue()) {
            abortClutch(player);
            return;
        }
        if (!ensureHoldingBlock(player)) {
            return;
        }

        if (bridgePath == null || bridgeIndex >= bridgePath.size()) {
            refreshBridgePlan(player);
        }

        PlacementCandidate placement = findBridgePlacement(player);
        if (placement == null) {
            refreshBridgePlan(player);
            placement = findBridgePlacement(player);
        }
        if (placement == null) {
            placement = findBestPlacement(player);
        }
        if (placement == null || !isPlacementWithinFov(player, placement)) {
            abortClutch(player);
            return;
        }

        float[] aim = faceAim(player, placement.neighbor, placement.face);
        setRotationTarget(aim[0], aim[1]);
        stepRotation(resolveAimSpeed());
        updateSilentModelRotation(player);
        applyVisibleRotation();

        if (!hasReachedTarget(2.0F)) {
            rotationHeldTicks = 0;
            return;
        }

        rotationHeldTicks = Math.min(2, rotationHeldTicks + 1);
        if (rotationHeldTicks < 2) {
            return;
        }
        if (!canPlaceNow()) {
            return;
        }
        if (!canAttemptPlacement(placement)) {
            return;
        }

        MovingObjectPosition hit = rayTraceAtRotation(player, getReach(player), currentYaw, currentPitch);
        if (hit == null
            || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || !placement.neighbor.equals(hit.getBlockPos())
            || hit.sideHit != placement.face) {
            return;
        }

        recordPlacementAttempt(placement);
        if (attemptPlacement(player, hit, placement)) {
            advanceBridgeState(placement);
            blocksPlaced++;
            lastHeldBlockSlot = heldBlockSlot(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        if (!isEnabled() || !silentAim.isEnabled() || !rotationActive || !isPlayerReady()) {
            return;
        }

        ClientRotationHelper helper = ClientRotationHelper.get();
        if (helper.requestRotations("Clutch", 95, currentYaw, currentPitch)
                || "Clutch".equals(helper.getRequestedOwner())) {
            event.yaw = Float.valueOf(currentYaw);
            event.pitch = Float.valueOf(currentPitch);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onForgeRenderPre(Event event) {
        if (!isLocalRenderEvent(event, "net.minecraftforge.client.event.RenderPlayerEvent$Pre")) return;
        beginModelRenderSwap(mc.thePlayer);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onForgeRenderPost(Event event) {
        if (!isLocalRenderEvent(event, "net.minecraftforge.client.event.RenderPlayerEvent$Post")) return;
        restoreModelRenderSwap();
    }

    private boolean isLocalRenderEvent(Event event, String expectedClassName) {
        return event != null
            && expectedClassName.equals(event.getClass().getName())
            && resolveRenderedPlayer(event) == mc.thePlayer;
    }

    private Object resolveRenderedPlayer(Event event) {
        try {
            if (renderPlayerField == null || !renderPlayerField.getDeclaringClass().isAssignableFrom(event.getClass())) {
                renderPlayerField = event.getClass().getField("entityPlayer");
                renderPlayerField.setAccessible(true);
            }
            return renderPlayerField.get(event);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        if (!shouldLockMovement()) {
            return;
        }

        if (clutching) {
            event.setForward(resolveReverseForwardInput(mc.thePlayer));
        } else {
            event.setForward(0.0F);
        }
        event.setStrafe(0.0F);
    }

    private void handleGroundState(EntityPlayerSP player) {
        if (clutching) {
            applyImmediateLandingLock(player);
            groundedClutchTicks++;
            if (groundedClutchTicks < 4) {
                return;
            }
            groundedClutchTicks = 0;
            restoreSavedSlot(player);

            moveFreezeTicks = Math.max(1, clutchMoveDelay.getValue());
            clutching = false;
            blocksPlaced = 0;

            if (rotateBack.isEnabled() && !silentAim.isEnabled()) {
                returningToCamera = true;
                snapBackDelayTicks = snapBackDelay.getValue();
                snapBackDurationTicks = snapBackDuration.getValue();
                setRotationTarget(savedCamYaw, savedCamPitch);
            } else {
                clearRotationState();
            }
        }
    }

    private void handleSnapBack(EntityPlayerSP player) {
        if (!returningToCamera) {
            return;
        }

        if (keepJumpDirection.isEnabled() && player != null && !player.onGround && player.motionY > 0.0D) {
            snapBackDelayTicks = 0;
            snapBackDurationTicks = 0;
            currentYaw = savedCamYaw;
            currentPitch = savedCamPitch;
        }

        if (snapBackDelayTicks > 0) {
            snapBackDelayTicks--;
            return;
        }

        stepRotation(resolveSnapBackSpeed());
        if (snapBackDurationTicks > 0) {
            snapBackDurationTicks--;
        }
        applyVisibleRotation();

        if (hasReachedTarget(2.0F)) {
            clearRotationState();
            returningToCamera = false;
            if (disableAfterwards.isEnabled()) {
                setEnabled(false);
            }
        }
    }

    private boolean triggerMet(EntityPlayerSP player) {
        int playerX = MathHelper.floor_double(player.posX);
        int playerZ = MathHelper.floor_double(player.posZ);
        int feetY = MathHelper.floor_double(player.posY);

        switch (trigger.getValue()) {
            case ALWAYS:
                return true;
            case ON_VOID:
                for (int y = feetY - 1; y >= feetY - 65; y--) {
                    if (isAttachableBlock(new BlockPos(playerX, y, playerZ))) {
                        return false;
                    }
                }
                return true;
            case ON_LETHAL_FALL:
                int lethalDepth = 0;
                for (int y = feetY - 1; y >= feetY - 41; y--) {
                    if (isAttachableBlock(new BlockPos(playerX, y, playerZ))) {
                        break;
                    }
                    lethalDepth++;
                }
                return player.fallDistance + lethalDepth - 3.0F >= player.getHealth() / 2.0F;
            case FALL_DISTANCE:
                int threshold = (int) blocks.getValue();
                int predictedDepth = 0;
                for (int y = feetY - 1; y >= feetY - threshold - 2; y--) {
                    if (isAttachableBlock(new BlockPos(playerX, y, playerZ))) {
                        break;
                    }
                    predictedDepth++;
                }
                return player.fallDistance + predictedDepth >= blocks.getValue();
            default:
                return false;
        }
    }

    private boolean conditionsMet(EntityPlayerSP player) {
        if (onlyMidAir.isEnabled() && player.onGround) {
            return false;
        }
        if (recentlyDamaged.isEnabled() && player.hurtTime <= 0) {
            return false;
        }
        if (movingBackwards.isEnabled() && resolveReverseForwardInput(player) == 0.0F) {
            return false;
        }
        return countAirBelow(player, minimumHeight.getValue()) >= minimumHeight.getValue();
    }

    private int countAirBelow(EntityPlayerSP player, int limit) {
        int playerX = MathHelper.floor_double(player.posX);
        int playerZ = MathHelper.floor_double(player.posZ);
        int feetY = MathHelper.floor_double(player.posY);
        int count = 0;
        for (int y = feetY - 1; y >= feetY - limit; y--) {
            if (!isAirBlock(new BlockPos(playerX, y, playerZ))) {
                break;
            }
            count++;
        }
        return count;
    }

    private int findBlockSlot(EntityPlayerSP player) {
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (!isValidBlockStack(stack)) {
                continue;
            }
            return slot;
        }

        return -1;
    }

    private boolean hasBlocks(EntityPlayerSP player) {
        if (selectBlocks.getValue() == SelectBlocksMode.NO) {
            return isValidBlockStack(player.getHeldItem());
        }
        return isValidBlockStack(player.getHeldItem()) || findBlockSlot(player) != -1;
    }

    private boolean ensureHoldingBlock(EntityPlayerSP player) {
        ItemStack heldItem = player.getHeldItem();
        if (isValidBlockStack(heldItem)) {
            slotSwitchPending = false;
            lastHeldBlockSlot = player.inventory.currentItem;
            return true;
        }

        if (selectBlocks.getValue() == SelectBlocksMode.NO) {
            return false;
        }

        if (selectBlocks.getValue() == SelectBlocksMode.ON_DEPLETION && lastHeldBlockSlot == -1) {
            return false;
        }

        if (slotSwitchPending) {
            return false;
        }

        int slot = findBlockSlot(player);
        if (slot == -1) {
            return false;
        }

        setSelectedSlot(slot);
        slotSwitchPending = true;
        return false;
    }

    private boolean isValidBlockStack(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemBlock) || stack.stackSize <= 0) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return block != null && block.isFullCube() && !(block instanceof BlockLiquid);
    }

    private int heldBlockSlot(EntityPlayerSP player) {
        return isValidBlockStack(player.getHeldItem()) ? player.inventory.currentItem : -1;
    }

    private PlacementCandidate findBestPlacement(EntityPlayerSP player) {
        double reach = getReach(player);
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        int blockX = MathHelper.floor_double(player.posX);
        int feetY = MathHelper.floor_double(player.posY);
        int blockZ = MathHelper.floor_double(player.posZ);
        double velocityY = Math.min(player.motionY, 0.0D);
        int targetY = MathHelper.floor_double(player.posY + velocityY) - 1;
        AxisAlignedBB trajectoryBox = player.getEntityBoundingBox().addCoord(0.0D, velocityY, 0.0D);

        PlacementCandidate best = null;
        int minDy = blocksPlaced > 0 ? -4 : -2;
        int scanRange = range.getValue();
        for (int dy = 0; dy >= minDy; dy--) {
            for (int dx = -scanRange; dx <= scanRange; dx++) {
                for (int dz = -scanRange; dz <= scanRange; dz++) {
                    BlockPos airPos = new BlockPos(blockX + dx, feetY + dy, blockZ + dz);
                    if (!isAirBlock(airPos)) {
                        continue;
                    }

                    AxisAlignedBB blockBox = new AxisAlignedBB(
                        airPos.getX(),
                        airPos.getY(),
                        airPos.getZ(),
                        airPos.getX() + 1.0D,
                        airPos.getY() + 1.0D,
                        airPos.getZ() + 1.0D
                    );
                    if (trajectoryBox.intersectsWith(blockBox)) {
                        continue;
                    }

                    for (EnumFacing direction : SEARCH_DIRECTIONS) {
                        BlockPos neighbor = airPos.offset(direction);
                        if (!isAttachableBlock(neighbor)) {
                            continue;
                        }
                        Block neighborBlock = mc.theWorld.getBlockState(neighbor).getBlock();
                        if (neighborBlock instanceof BlockLiquid) {
                            continue;
                        }

                        EnumFacing face = direction.getOpposite();
                        if (!isAllowedFace(face)) {
                            continue;
                        }
                        double hitX = neighbor.getX() + 0.5D + face.getFrontOffsetX() * 0.45D;
                        double hitY = neighbor.getY() + 0.5D + face.getFrontOffsetY() * 0.45D;
                        double hitZ = neighbor.getZ() + 0.5D + face.getFrontOffsetZ() * 0.45D;
                        double deltaX = hitX - eyeX;
                        double deltaY = hitY - eyeY;
                        double deltaZ = hitZ - eyeZ;
                        if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > reach * reach) {
                            continue;
                        }
                        PlacementCandidate candidate = new PlacementCandidate(airPos, neighbor, face, 0.0D);
                        if (!isPlacementWithinFov(player, candidate)) {
                            continue;
                        }

                        double yDeviation = Math.abs(airPos.getY() - targetY);
                        double horizontalDistance = Math.sqrt(
                            (airPos.getX() + 0.5D - player.posX) * (airPos.getX() + 0.5D - player.posX)
                                + (airPos.getZ() + 0.5D - player.posZ) * (airPos.getZ() + 0.5D - player.posZ)
                        );
                        double score = yDeviation * 20.0D + horizontalDistance;
                        if (dx == 0 && dz == 0) {
                            score += 6.0D;
                        }
                        if (best == null || score < best.score) {
                            best = new PlacementCandidate(airPos, neighbor, face, score);
                        }
                    }
                }
            }
        }

        return best;
    }

    private PlacementCandidate findBridgePlacement(EntityPlayerSP player) {
        if (bridgePath == null || bridgeIndex >= bridgePath.size()) {
            return null;
        }

        while (bridgeIndex < bridgePath.size()) {
            BlockPos target = bridgePath.get(bridgeIndex);
            if (!isAirBlock(target)) {
                bridgeIndex++;
                continue;
            }

            PlacementCandidate placement;
            if (bridgeIndex == 0 && bridgeStartPlacement != null) {
                placement = bridgeStartPlacement;
            } else {
                placement = null;
                if (bridgeIndex > 0) {
                    placement = makePlacementAgainst(target, bridgePath.get(bridgeIndex - 1));
                }
                if (placement == null) {
                    placement = findPlacementInfo(target);
                }
            }

            if (placement == null) {
                return null;
            }
            if (!isAllowedFace(placement.face)) {
                bridgeIndex++;
                continue;
            }
            if (!isPlacementReachable(player, placement.neighbor, placement.face, getReach(player))) {
                return null;
            }
            if (!isPlacementWithinFov(player, placement)) {
                return null;
            }

            return placement;
        }

        return null;
    }

    private float[] faceAim(EntityPlayerSP player, BlockPos neighbor, EnumFacing face) {
        double eyeY = player.posY + player.getEyeHeight();
        double[] hit = hitCoordinates(player, neighbor, face);
        double hitX = hit[0];
        double hitY = hit[1];
        double hitZ = hit[2];
        double deltaX = hitX - player.posX;
        double deltaY = hitY - eyeY;
        double deltaZ = hitZ - player.posZ;
        double horizontalDistance = Math.max(0.01D, Math.sqrt(deltaX * deltaX + deltaZ * deltaZ));
        float yaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        float pitch = MathHelper.clamp_float((float) Math.toDegrees(Math.atan2(-deltaY, horizontalDistance)), -90.0F, 90.0F);
        return new float[] {yaw, pitch};
    }

    private double[] hitCoordinates(EntityPlayerSP player, BlockPos neighbor, EnumFacing face) {
        double hitX = neighbor.getX() + 0.5D + face.getFrontOffsetX() * 0.45D;
        double hitY = neighbor.getY() + 0.5D + face.getFrontOffsetY() * 0.45D;
        double hitZ = neighbor.getZ() + 0.5D + face.getFrontOffsetZ() * 0.45D;

        if (multipoint.isEnabled() && player != null) {
            double eyeY = player.posY + player.getEyeHeight();
            if (face.getFrontOffsetX() == 0) {
                hitX = MathHelper.clamp_double(player.posX, neighbor.getX() + 0.08D, neighbor.getX() + 0.92D);
            }
            if (face.getFrontOffsetY() == 0) {
                hitY = MathHelper.clamp_double(eyeY, neighbor.getY() + 0.08D, neighbor.getY() + 0.92D);
            }
            if (face.getFrontOffsetZ() == 0) {
                hitZ = MathHelper.clamp_double(player.posZ, neighbor.getZ() + 0.08D, neighbor.getZ() + 0.92D);
            }
        }

        int jitter = randomization.getValue();
        if (jitter > 0) {
            double spread = 0.12D * (jitter / 100.0D);
            if (face.getFrontOffsetX() == 0) {
                hitX += (random.nextDouble() * 2.0D - 1.0D) * spread;
            }
            if (face.getFrontOffsetY() == 0) {
                hitY += (random.nextDouble() * 2.0D - 1.0D) * spread;
            }
            if (face.getFrontOffsetZ() == 0) {
                hitZ += (random.nextDouble() * 2.0D - 1.0D) * spread;
            }
            hitX = MathHelper.clamp_double(hitX, neighbor.getX() + 0.02D, neighbor.getX() + 0.98D);
            hitY = MathHelper.clamp_double(hitY, neighbor.getY() + 0.02D, neighbor.getY() + 0.98D);
            hitZ = MathHelper.clamp_double(hitZ, neighbor.getZ() + 0.02D, neighbor.getZ() + 0.98D);
        }

        return new double[] {hitX, hitY, hitZ};
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
        if (!rotationActive || maxDegrees <= 0.0F || mc.gameSettings == null) {
            return;
        }

        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(targetPitch - currentPitch);
        float distance = MathHelper.sqrt_float(yawDiff * yawDiff + pitchDiff * pitchDiff);
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 1.2F;

        if (distance <= maxDegrees) {
            snapToTarget(gcd);
            return;
        }

        float ratio = maxDegrees / distance;
        int mouseDx = Math.round((yawDiff * ratio) / gcd);
        int mouseDy = Math.round((pitchDiff * ratio) / gcd);
        currentYaw += mouseDx * gcd;
        currentPitch = MathHelper.clamp_float(currentPitch + mouseDy * gcd, -90.0F, 90.0F);
    }

    private float resolveAimSpeed() {
        float base = (float) rotationSpeed.getValue();
        if (aimAcceleration.getValue() <= 0 || !rotationActive) {
            return randomizeSpeed(base);
        }

        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(targetPitch - currentPitch);
        float distance = MathHelper.sqrt_float(yawDiff * yawDiff + pitchDiff * pitchDiff);
        float accelerated = Math.max(base, (float) aimAcceleration.getValue() * Math.min(1.0F, distance / 90.0F));
        float strength = accelerationStrength.getValue() / 100.0F;
        return randomizeSpeed(base * (1.0F - strength) + accelerated * strength);
    }

    private float resolveSnapBackSpeed() {
        if (snapBackDuration.getValue() <= 0 && snapBackDurationTicks <= 0) {
            return (float) rotationSpeed.getValue();
        }
        if (!rotationActive) {
            return (float) rotationSpeed.getValue();
        }
        int ticks = snapBackDurationTicks > 0 ? snapBackDurationTicks : 1;
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - currentYaw));
        float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetPitch - currentPitch));
        return Math.max(1.0F, MathHelper.sqrt_float(yawDiff * yawDiff + pitchDiff * pitchDiff) / ticks);
    }

    private float randomizeSpeed(float speed) {
        int jitter = randomization.getValue();
        if (jitter <= 0) {
            return speed;
        }
        double spread = 0.18D * (jitter / 100.0D);
        double factor = 1.0D + (random.nextDouble() * 2.0D - 1.0D) * spread;
        return Math.max(1.0F, (float) (speed * factor));
    }

    private void snapToTarget(float gcd) {
        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;
        int mouseDx = Math.round(yawDiff / gcd);
        int mouseDy = Math.round(pitchDiff / gcd);
        currentYaw += mouseDx * gcd;
        currentPitch = MathHelper.clamp_float(currentPitch + mouseDy * gcd, -90.0F, 90.0F);
    }

    private boolean hasReachedTarget(float toleranceDegrees) {
        if (!rotationActive) {
            return true;
        }

        return Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - currentYaw)) <= toleranceDegrees
            && Math.abs(MathHelper.wrapAngleTo180_float(targetPitch - currentPitch)) <= toleranceDegrees;
    }

    private void applyVisibleRotation() {
        if (silentAim.isEnabled() || mc.thePlayer == null || !rotationActive) {
            return;
        }

        mc.thePlayer.rotationYaw = currentYaw;
        mc.thePlayer.rotationPitch = currentPitch;
    }

    private MovingObjectPosition rayTraceAtRotation(EntityPlayerSP player, double reach, float yaw, float pitch) {
        float savedYaw = player.rotationYaw;
        float savedPitch = player.rotationPitch;

        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        try {
            return player.rayTrace(reach, 1.0F);
        } finally {
            player.rotationYaw = savedYaw;
            player.rotationPitch = savedPitch;
        }
    }

    private boolean attemptPlacement(EntityPlayerSP player, MovingObjectPosition hit, PlacementCandidate placement) {
        if (mc.playerController == null || hit == null || hit.hitVec == null) {
            return false;
        }

        ItemStack heldItem = player.getHeldItem();
        if (heldItem == null || !(heldItem.getItem() instanceof ItemBlock)) {
            return false;
        }

        MovingObjectPosition previousHit = mc.objectMouseOver;
        mc.objectMouseOver = hit;
        try {
            boolean placed = mc.playerController.onPlayerRightClick(
                player,
                mc.theWorld,
                heldItem,
                hit.getBlockPos(),
                hit.sideHit,
                hit.hitVec
            );
            if (placed) {
                player.swingItem();
            }
            return placed;
        } catch (NoSuchMethodError | AbstractMethodError unavailable) {
            // The raw packet path is only a compatibility fallback. A false controller result
            // may already have transmitted C08, so it must never trigger a second placement.
            return sendPlacementFallback(player, heldItem, hit, placement);
        } finally {
            mc.objectMouseOver = previousHit;
        }
    }

    private boolean sendPlacementFallback(EntityPlayerSP player, ItemStack heldItem, MovingObjectPosition hit, PlacementCandidate placement) {
        if (player == null
            || player.sendQueue == null
            || heldItem == null
            || hit == null
            || hit.hitVec == null
            || placement == null
            || !placement.neighbor.equals(hit.getBlockPos())
            || placement.face != hit.sideHit
            || !isValidBlockStack(heldItem)) {
            return false;
        }

        float hitX = (float) (hit.hitVec.xCoord - hit.getBlockPos().getX());
        float hitY = (float) (hit.hitVec.yCoord - hit.getBlockPos().getY());
        float hitZ = (float) (hit.hitVec.zCoord - hit.getBlockPos().getZ());
        player.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(
            hit.getBlockPos(),
            hit.sideHit.getIndex(),
            heldItem,
            MathHelper.clamp_float(hitX, 0.0F, 1.0F),
            MathHelper.clamp_float(hitY, 0.0F, 1.0F),
            MathHelper.clamp_float(hitZ, 0.0F, 1.0F)
        ));
        player.swingItem();
        return true;
    }

    private boolean canPlaceNow() {
        if (lastPlacementAttemptNanos <= 0L) {
            return true;
        }
        return System.nanoTime() - lastPlacementAttemptNanos >= nextPlacementDelayNanos;
    }

    private boolean canAttemptPlacement(PlacementCandidate placement) {
        if (placement == null || placement.neighbor == null || placement.face == null) {
            return false;
        }
        if (lastPlacementTarget == null || lastPlacementFace == null) {
            return true;
        }
        if (!lastPlacementTarget.equals(placement.neighbor) || lastPlacementFace != placement.face) {
            return true;
        }
        long retryDelay = Math.max(50000000L, nextPlacementDelayNanos);
        return System.nanoTime() - lastPlacementAttemptNanos >= retryDelay;
    }

    private void recordPlacementAttempt(PlacementCandidate placement) {
        lastPlacementAttemptNanos = System.nanoTime();
        nextPlacementDelayNanos = nextPlacementDelay();
        lastPlacementTarget = placement.neighbor;
        lastPlacementFace = placement.face;
    }

    private long nextPlacementDelay() {
        int cps = Math.max(1, clickSpeed.getValue());
        double delay = 1000000000.0D / cps;
        int jitter = randomization.getValue();
        if (jitter > 0) {
            double spread = 0.30D * (jitter / 100.0D);
            delay *= 1.0D + (random.nextDouble() * 2.0D - 1.0D) * spread;
        }
        return Math.max(25000000L, (long) delay);
    }

    private void advanceBridgeState(PlacementCandidate placement) {
        if (bridgePath == null || bridgeIndex >= bridgePath.size() || placement == null || placement.targetPos == null) {
            return;
        }

        if (placement.targetPos.equals(bridgePath.get(bridgeIndex))) {
            bridgeIndex++;
            if (bridgeIndex > 0) {
                bridgeStartPlacement = null;
            }
        }
    }

    private double getReach(EntityPlayerSP player) {
        return player.capabilities.isCreativeMode ? 5.0D : 4.5D;
    }

    private boolean isAirBlock(BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock().getMaterial() == Material.air;
    }

    private boolean isAttachableBlock(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block.getMaterial() != Material.air && !(block instanceof BlockLiquid);
    }

    private boolean isPlacementReachable(EntityPlayerSP player, BlockPos neighbor, EnumFacing face, double reach) {
        double eyeX = player.posX;
        double eyeY = player.posY + player.getEyeHeight();
        double eyeZ = player.posZ;
        double[] hit = hitCoordinates(player, neighbor, face);
        double hitX = hit[0];
        double hitY = hit[1];
        double hitZ = hit[2];
        double deltaX = hitX - eyeX;
        double deltaY = hitY - eyeY;
        double deltaZ = hitZ - eyeZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= reach * reach;
    }

    private List<BlockPos> buildBridgePath(EntityPlayerSP player) {
        PlacementCandidate edge = findNearestEdge(player);
        if (edge == null || edge.targetPos == null) {
            return null;
        }

        double futureX = player.posX + player.motionX;
        double futureZ = player.posZ + player.motionZ;
        int playerX = MathHelper.floor_double(futureX);
        int playerZ = MathHelper.floor_double(futureZ);
        int estimatedLength = Math.abs(edge.targetPos.getX() - playerX) + Math.abs(edge.targetPos.getZ() - playerZ);
        if (estimatedLength < 1) {
            estimatedLength = 1;
        }

        double predictedY = predictYAfterTicks(player, estimatedLength);
        int bridgeY = MathHelper.floor_double(predictedY) - 1;
        int supportY = edge.neighbor.getY();
        if (bridgeY > supportY) {
            bridgeY = supportY;
        }

        BlockPos bridgeStart = new BlockPos(edge.targetPos.getX(), bridgeY, edge.targetPos.getZ());
        BlockPos playerColumn = new BlockPos(playerX, bridgeY, playerZ);
        List<BlockPos> path = calculateBridgePath(bridgeStart, playerColumn);
        if (path.isEmpty()) {
            return null;
        }

        double fracX = futureX - Math.floor(futureX);
        double fracZ = futureZ - Math.floor(futureZ);
        BlockPos last = path.get(path.size() - 1);
        if (fracX >= 0.7D) {
            path.add(new BlockPos(last.getX() + 1, bridgeY, last.getZ()));
        } else if (fracX <= 0.3D) {
            path.add(new BlockPos(last.getX() - 1, bridgeY, last.getZ()));
        }

        last = path.get(path.size() - 1);
        if (fracZ >= 0.7D) {
            path.add(new BlockPos(last.getX(), bridgeY, last.getZ() + 1));
        } else if (fracZ <= 0.3D) {
            path.add(new BlockPos(last.getX(), bridgeY, last.getZ() - 1));
        }

        int availableBlocks = countAvailableBlocks(player);
        if (path.size() > availableBlocks) {
            path = new ArrayList<BlockPos>(path.subList(0, availableBlocks));
        }
        if (path.isEmpty()) {
            return null;
        }

        PlacementCandidate startPlacement = findPlacementInfo(path.get(0));
        if (startPlacement != null) {
            bridgeStartPlacement = startPlacement;
        } else {
            bridgeStartPlacement = new PlacementCandidate(path.get(0), edge.neighbor, edge.face, edge.score);
        }
        return path;
    }

    private void refreshBridgePlan(EntityPlayerSP player) {
        bridgeStartPlacement = null;
        bridgePath = buildBridgePath(player);
        bridgeIndex = 0;
    }

    private PlacementCandidate findNearestEdge(EntityPlayerSP player) {
        int playerX = MathHelper.floor_double(player.posX);
        int playerY = MathHelper.floor_double(player.posY);
        int playerZ = MathHelper.floor_double(player.posZ);
        PlacementCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        int range = this.range.getValue();
        double reach = getReach(player);

        for (int dy = -4; dy <= 1; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos solidPos = new BlockPos(playerX + dx, playerY + dy, playerZ + dz);
                    if (!isAttachableBlock(solidPos)) {
                        continue;
                    }

                    for (EnumFacing face : EnumFacing.values()) {
                        if (!isAllowedFace(face)) {
                            continue;
                        }
                        BlockPos airPos = solidPos.offset(face);
                        if (!isAirBlock(airPos)) {
                            continue;
                        }
                        if (!isPlacementReachable(player, solidPos, face, reach)) {
                            continue;
                        }
                        PlacementCandidate candidate = new PlacementCandidate(airPos, solidPos, face, 0.0D);
                        if (!isPlacementWithinFov(player, candidate)) {
                            continue;
                        }

                        double horizontalDistance = Math.sqrt(
                            (airPos.getX() + 0.5D - player.posX) * (airPos.getX() + 0.5D - player.posX)
                                + (airPos.getZ() + 0.5D - player.posZ) * (airPos.getZ() + 0.5D - player.posZ)
                        );
                        double verticalDistance = Math.abs(airPos.getY() - (playerY - 1));
                        double score = horizontalDistance + verticalDistance * 3.0D;
                        if (best == null || score < bestScore) {
                            bestScore = score;
                            best = new PlacementCandidate(airPos, solidPos, face, score);
                        }
                    }
                }
            }
        }

        return best;
    }

    private PlacementCandidate findPlacementInfo(BlockPos targetPos) {
        if (!isAirBlock(targetPos)) {
            return null;
        }

        for (EnumFacing face : PLACEMENT_FACE_PRIORITY) {
            BlockPos neighbor = targetPos.offset(face);
            if (!isAttachableBlock(neighbor)) {
                continue;
            }
            EnumFacing placementFace = face.getOpposite();
            if (!isAllowedFace(placementFace)) {
                continue;
            }
            return new PlacementCandidate(targetPos, neighbor, placementFace, 0.0D);
        }

        return null;
    }

    private PlacementCandidate makePlacementAgainst(BlockPos targetPos, BlockPos neighbor) {
        if (!isAttachableBlock(neighbor)) {
            return null;
        }

        int dx = targetPos.getX() - neighbor.getX();
        int dy = targetPos.getY() - neighbor.getY();
        int dz = targetPos.getZ() - neighbor.getZ();

        EnumFacing face;
        if (dx == 1) {
            face = EnumFacing.EAST;
        } else if (dx == -1) {
            face = EnumFacing.WEST;
        } else if (dz == 1) {
            face = EnumFacing.SOUTH;
        } else if (dz == -1) {
            face = EnumFacing.NORTH;
        } else if (dy == 1) {
            face = EnumFacing.UP;
        } else if (dy == -1) {
            face = EnumFacing.DOWN;
        } else {
            return null;
        }

        if (!isAllowedFace(face)) {
            return null;
        }
        return new PlacementCandidate(targetPos, neighbor, face, 0.0D);
    }

    private List<BlockPos> calculateBridgePath(BlockPos from, BlockPos to) {
        List<BlockPos> path = new ArrayList<BlockPos>();
        path.add(from);

        int x = from.getX();
        int z = from.getZ();
        int y = from.getY();
        int targetX = to.getX();
        int targetZ = to.getZ();

        while (x != targetX || z != targetZ) {
            int dx = targetX - x;
            int dz = targetZ - z;
            if (Math.abs(dx) >= Math.abs(dz)) {
                x += dx > 0 ? 1 : -1;
            } else {
                z += dz > 0 ? 1 : -1;
            }
            path.add(new BlockPos(x, y, z));
        }

        return path;
    }

    private double predictYAfterTicks(EntityPlayerSP player, int ticks) {
        double y = player.posY;
        double velocityY = player.motionY;
        for (int i = 0; i < ticks; i++) {
            velocityY = (velocityY - 0.08D) * 0.98D;
            y += velocityY;
        }
        return y;
    }

    private int countAvailableBlocks(EntityPlayerSP player) {
        int total = 0;
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (!isValidBlockStack(stack)) {
                continue;
            }

            total += stack.stackSize;
        }
        return total;
    }

    private boolean isAllowedFace(EnumFacing face) {
        return !onlyPlaceSideways.isEnabled() || (face != EnumFacing.UP && face != EnumFacing.DOWN);
    }

    private boolean isPlacementWithinFov(EntityPlayerSP player, PlacementCandidate placement) {
        if (fov.getValue() >= 180 || placement == null) {
            return true;
        }
        float[] aim = faceAim(player, placement.neighbor, placement.face);
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(aim[0] - player.rotationYaw));
        float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(aim[1] - player.rotationPitch));
        float angle = MathHelper.sqrt_float(yawDiff * yawDiff + pitchDiff * pitchDiff);
        return angle <= fov.getValue() * 0.5F;
    }

    private void setSelectedSlot(int slot) {
        if (!isPlayerReady() || mc.thePlayer.inventory.currentItem == slot) {
            return;
        }

        mc.thePlayer.inventory.currentItem = slot;
        if (mc.playerController != null) {
            mc.playerController.updateController();
        }
    }

    private void restoreSavedSlot(EntityPlayerSP player) {
        ResourceArbiter.Lease lease = slotLease;
        slotLease = null;
        if (lease != null && lease.isValid()) lease.close();
        savedSlot = -1;
    }

    private boolean acquireSlotLease(final EntityPlayerSP player) {
        if (slotLease != null) return slotLease.renew(2);
        final int originalSlot = player.inventory.currentItem;
        slotLease = getScope().acquire(ResourceArbiter.Resource.HOTBAR_SLOT, 400, 2, new Runnable() {
            @Override
            public void run() {
                if (!returnToSlot.isEnabled() || mc.thePlayer != player || originalSlot < 0 || originalSlot > 8) return;
                player.inventory.currentItem = originalSlot;
                if (mc.playerController != null) mc.playerController.syncCurrentPlayItem();
            }
        });
        return slotLease != null;
    }

    private void clearRotationState() {
        clearSilentModelRotation();
        rotationActive = false;
        targetYaw = 0.0F;
        targetPitch = 0.0F;
        rotationHeldTicks = 0;
        ClientRotationHelper helper = ClientRotationHelper.get();
        if ("Clutch".equals(helper.getRequestedOwner())) helper.clearRequestedRotations();
    }

    private void updateSilentModelRotation(EntityPlayerSP player) {
        if (player == null || !silentAim.isEnabled() || !rotationActive) {
            clearSilentModelRotation();
            return;
        }

        ClientRotationHelper.get().requestRotations("Clutch", 95, currentYaw, currentPitch);
        if (!acquireModelRotationLease()) {
            restoreModelRenderSwap();
            modelRotationActive = false;
            return;
        }

        if (modelRotationActive) {
            previousSilentYaw = lastSilentYaw;
            previousSilentPitch = lastSilentPitch;
            lastSilentYaw = ClientRotationHelper.unwrapYaw(currentYaw, lastSilentYaw);
        } else {
            lastSilentYaw = ClientRotationHelper.unwrapYaw(currentYaw, player.rotationYawHead);
            previousSilentYaw = ClientRotationHelper.unwrapYaw(player.prevRotationYawHead, lastSilentYaw);
            previousSilentPitch = player.prevRotationPitch;
        }
        lastSilentPitch = MathHelper.clamp_float(currentPitch, -90.0F, 90.0F);
        modelRotationActive = true;
    }

    private boolean acquireModelRotationLease() {
        if (modelRotationLease != null && modelRotationLease.renew(2)) return true;
        modelRotationLease = getScope().acquire(ResourceArbiter.Resource.MODEL_ROTATION, 95, 2, new Runnable() {
            @Override
            public void run() {
                restoreModelRenderSwap();
            }
        });
        return modelRotationLease != null;
    }

    private void beginModelRenderSwap(EntityPlayerSP player) {
        restoreModelRenderSwap();
        if (player == null
            || !isEnabled()
            || !silentAim.isEnabled()
            || !modelRotationActive
            || modelRotationLease == null
            || !modelRotationLease.isValid()) {
            return;
        }

        modelRenderPlayer = player;
        savedRenderYawHead = player.rotationYawHead;
        savedPrevRenderYawHead = player.prevRotationYawHead;
        savedRenderYawOffset = player.renderYawOffset;
        savedPrevRenderYawOffset = player.prevRenderYawOffset;
        savedRenderPitch = player.rotationPitch;
        savedPrevRenderPitch = player.prevRotationPitch;

        player.rotationYawHead = lastSilentYaw;
        player.prevRotationYawHead = previousSilentYaw;
        player.renderYawOffset = lastSilentYaw;
        player.prevRenderYawOffset = previousSilentYaw;
        player.rotationPitch = lastSilentPitch;
        player.prevRotationPitch = previousSilentPitch;
        modelRenderSwapActive = true;
    }

    private void restoreModelRenderSwap() {
        EntityPlayerSP player = modelRenderPlayer;
        if (modelRenderSwapActive && player != null) {
            player.rotationYawHead = savedRenderYawHead;
            player.prevRotationYawHead = savedPrevRenderYawHead;
            player.renderYawOffset = savedRenderYawOffset;
            player.prevRenderYawOffset = savedPrevRenderYawOffset;
            player.rotationPitch = savedRenderPitch;
            player.prevRotationPitch = savedPrevRenderPitch;
        }
        modelRenderSwapActive = false;
        modelRenderPlayer = null;
    }

    private void clearSilentModelRotation() {
        restoreModelRenderSwap();
        ResourceArbiter.Lease lease = modelRotationLease;
        modelRotationLease = null;
        if (lease != null && lease.isValid()) lease.close();
        modelRotationActive = false;
        previousSilentYaw = 0.0F;
        lastSilentYaw = 0.0F;
        previousSilentPitch = 0.0F;
        lastSilentPitch = 0.0F;
    }

    private void resetState() {
        ResourceArbiter.Lease lease = slotLease;
        if (lease != null && lease.isValid()) lease.close();
        blocksPlaced = 0;
        slotLease = null;
        savedSlot = -1;
        moveFreezeTicks = 0;
        groundedClutchTicks = 0;
        snapBackDelayTicks = 0;
        snapBackDurationTicks = 0;
        slotSwitchPending = false;
        clutching = false;
        returningToCamera = false;
        rotationHeldTicks = 0;
        bridgePath = null;
        bridgeIndex = 0;
        bridgeStartPlacement = null;
        lastPlacementAttemptNanos = 0L;
        nextPlacementDelayNanos = 0L;
        lastPlacementTarget = null;
        lastPlacementFace = null;
        lastHeldBlockSlot = -1;
    }

    private void abortClutch(EntityPlayerSP player) {
        if (!clutching && !rotationActive && savedSlot == -1 && slotLease == null
                && bridgePath == null && !slotSwitchPending) {
            return;
        }

        restoreSavedSlot(player);
        resetState();
        clearRotationState();
    }

    @Override
    public String getHudInfo() {
        return clutching ? blocksPlaced + "/" + maxBlocks.getValue() : "Ready";
    }

    private void abortActiveClutch(EntityPlayerSP player) {
        if (clutching) {
            abortClutch(player);
        }
    }

    private boolean shouldLockMovement() {
        return isEnabled() && isPlayerReady() && (clutching || returningToCamera || moveFreezeTicks > 0);
    }

    private void applyImmediateLandingLock(EntityPlayerSP player) {
        if (player == null || player.movementInput == null) {
            return;
        }

        player.moveForward = clutching ? resolveReverseForwardInput(player) : 0.0F;
        player.moveStrafing = 0.0F;
        player.movementInput.moveForward = player.moveForward;
        player.movementInput.moveStrafe = 0.0F;
    }

    private float resolveReverseForwardInput(EntityPlayerSP player) {
        if (player == null) {
            return 0.0F;
        }

        double motionX = player.motionX;
        double motionZ = player.motionZ;
        double horizontalSpeedSq = motionX * motionX + motionZ * motionZ;
        if (horizontalSpeedSq < 1.0E-4D) {
            return 0.0F;
        }

        double yawRadians = Math.toRadians(player.rotationYaw);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double projectedForwardMotion = motionX * forwardX + motionZ * forwardZ;
        if (projectedForwardMotion > 0.01D) {
            return -1.0F;
        }
        if (projectedForwardMotion < -0.01D) {
            return 1.0F;
        }
        return 0.0F;
    }

    private boolean isPlayerReady() {
        return mc.thePlayer != null && mc.theWorld != null && !mc.thePlayer.isDead;
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

    public enum Trigger {
        ALWAYS,
        ON_VOID,
        ON_LETHAL_FALL,
        FALL_DISTANCE
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

        SelectBlocksMode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private static final class PlacementCandidate {
        private final BlockPos targetPos;
        private final BlockPos neighbor;
        private final EnumFacing face;
        private final double score;

        private PlacementCandidate(BlockPos targetPos, BlockPos neighbor, EnumFacing face, double score) {
            this.targetPos = targetPos;
            this.neighbor = neighbor;
            this.face = face;
            this.score = score;
        }
    }
}
