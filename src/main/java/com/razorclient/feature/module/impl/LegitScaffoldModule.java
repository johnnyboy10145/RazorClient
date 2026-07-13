package com.razorclient.feature.module.impl;

import com.razorclient.combat.CombatActionCoordinator;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class LegitScaffoldModule extends Module {
    private static final double MIN_INPUT = 1.0E-4D;
    private static final double SUPPORT_SAMPLE_DEPTH = 0.08D;
    private static final float PITCH_THRESHOLD = 45.0F;

    private final BooleanSetting pitchCheck = new BooleanSetting("Pitch Check", false);
    private final NumberSetting sneakDelay = new NumberSetting("Sneak Delay", 0, 250, 5, 60);
    private final EnumSetting<DirectionMode> directionMode =
        new EnumSetting<DirectionMode>("Direction Mode", DirectionMode.values(), DirectionMode.ANY_DIRECTION);
    private final BooleanSetting diagonalAssist = new BooleanSetting("Diagonal Assist", true);
    private final NumberSetting diagonalReleaseDelay =
        new NumberSetting("Diagonal Release Delay", 0, 150, 5, 25);
    private final NumberSetting edgeDistance = new NumberSetting("Edge Distance", 10, 50, 1, 30);
    private final BooleanSetting placementAssist = new BooleanSetting("Placement Assist", false);
    private final NumberSetting placementCps = new NumberSetting("Placement CPS", 1, 25, 1, 12);
    private final BooleanSetting requireRightClick = new BooleanSetting("Require Right Click", true);

    private long sneakReleaseTime;
    private long nextPlacementTime;
    private boolean moduleSneaking;
    private boolean diagonalMovement;
    private Status status = Status.READY;

    public LegitScaffoldModule() {
        super("LegitScaffold", "Sneaks at block edges and can assist normal block placement.", Category.MOVEMENT, Keyboard.KEY_NONE);
        placementCps.setVisibility(() -> placementAssist.isEnabled());
        requireRightClick.setVisibility(() -> placementAssist.isEnabled());
        addSetting(pitchCheck);
        addSetting(sneakDelay);
        addSetting(directionMode);
        addSetting(diagonalAssist);
        addSetting(diagonalReleaseDelay);
        addSetting(edgeDistance);
        addSetting(placementAssist);
        addSetting(placementCps);
        addSetting(requireRightClick);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null || minecraft.theWorld == null || minecraft.gameSettings == null) {
            clearState(minecraft);
            return;
        }

        if (minecraft.currentScreen != null || !minecraft.inGameHasFocus || player.isDead) {
            clearState(minecraft);
            return;
        }

        int sneakKey = minecraft.gameSettings.keyBindSneak.getKeyCode();
        boolean physicalSneak = isPhysicalKeyDown(sneakKey);
        boolean active = passesActivation(player);
        boolean unsafe = active && shouldSneakAtEdge(player, minecraft.theWorld);
        long now = System.nanoTime();

        if (unsafe) {
            moduleSneaking = true;
            status = diagonalMovement ? Status.DIAGONAL : Status.EDGE;
            KeyBinding.setKeyBindState(sneakKey, true);
            sneakReleaseTime = Long.MAX_VALUE;
            tryPlacementAssist(minecraft, now);
            return;
        }

        if (moduleSneaking && sneakReleaseTime == Long.MAX_VALUE) {
            int delay = diagonalMovement && diagonalAssist.isEnabled()
                ? diagonalReleaseDelay.getValue()
                : sneakDelay.getValue();
            sneakReleaseTime = now + delay * 1000000L;
        }

        if (moduleSneaking && now < sneakReleaseTime) {
            KeyBinding.setKeyBindState(sneakKey, true);
            return;
        }

        moduleSneaking = false;
        sneakReleaseTime = 0L;
        status = Status.READY;
        KeyBinding.setKeyBindState(sneakKey, physicalSneak);
    }

    @Override
    protected void onDisable() {
        clearState(Minecraft.getMinecraft());
    }

    @Override
    public void onSessionReset() {
        clearState(Minecraft.getMinecraft());
    }

    @Override
    public void onInputContextLost() {
        clearState(Minecraft.getMinecraft());
    }

    private boolean passesActivation(EntityPlayerSP player) {
        if (!player.onGround || player.isCollidedHorizontally || player.movementInput == null) {
            return false;
        }
        if (pitchCheck.isEnabled() && player.rotationPitch < PITCH_THRESHOLD) {
            return false;
        }
        if (directionMode.getValue() == DirectionMode.BACKWARD_ONLY
            && player.movementInput.moveForward >= -0.01F) {
            return false;
        }
        return directionMode.getValue() != DirectionMode.BLOCKS_HELD || isHoldingValidBlock(player);
    }

    private boolean shouldSneakAtEdge(EntityPlayerSP player, World world) {
        float forward = player.movementInput.moveForward;
        float strafe = player.movementInput.moveStrafe;
        double inputLength = Math.sqrt(forward * forward + strafe * strafe);
        if (inputLength < MIN_INPUT) {
            diagonalMovement = false;
            return false;
        }

        forward /= inputLength;
        strafe /= inputLength;
        diagonalMovement = Math.abs(forward) > 0.25F && Math.abs(strafe) > 0.25F;

        double yaw = Math.toRadians(player.rotationYaw);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double directionX = strafe * cos - forward * sin;
        double directionZ = forward * cos + strafe * sin;
        double momentumX = MathHelper.clamp_double(player.motionX, -0.18D, 0.18D);
        double momentumZ = MathHelper.clamp_double(player.motionZ, -0.18D, 0.18D);
        double projection = edgeDistance.getValue() / 100.0D;
        double offsetX = directionX * projection + momentumX * 0.35D;
        double offsetZ = directionZ * projection + momentumZ * 0.35D;

        AxisAlignedBB projected = player.getEntityBoundingBox().offset(offsetX, 0.0D, offsetZ);
        double sampleY = projected.minY - SUPPORT_SAMPLE_DEPTH;
        double centerX = (projected.minX + projected.maxX) * 0.5D;
        double centerZ = (projected.minZ + projected.maxZ) * 0.5D;
        double halfX = (projected.maxX - projected.minX) * 0.48D;
        double halfZ = (projected.maxZ - projected.minZ) * 0.48D;

        if (diagonalMovement && diagonalAssist.isEnabled()) {
            double cornerX = directionX >= 0.0D ? projected.maxX - 0.02D : projected.minX + 0.02D;
            double cornerZ = directionZ >= 0.0D ? projected.maxZ - 0.02D : projected.minZ + 0.02D;
            return !hasSupport(world, cornerX, sampleY, cornerZ)
                || !hasSupport(world, centerX, sampleY, centerZ);
        }

        double lateralX = -directionZ;
        double lateralZ = directionX;
        return !hasSupport(world, centerX, sampleY, centerZ)
            || (!hasSupport(world, centerX + lateralX * halfX, sampleY, centerZ + lateralZ * halfZ)
                && !hasSupport(world, centerX - lateralX * halfX, sampleY, centerZ - lateralZ * halfZ));
    }

    private boolean hasSupport(World world, double x, double y, double z) {
        BlockPos position = new BlockPos(MathHelper.floor_double(x), MathHelper.floor_double(y), MathHelper.floor_double(z));
        IBlockState state = world.getBlockState(position);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || material.isLiquid() || block.isReplaceable(world, position)) {
            return false;
        }
        return block.getCollisionBoundingBox(world, position, state) != null;
    }

    private void tryPlacementAssist(Minecraft minecraft, long now) {
        if (!placementAssist.isEnabled() || now < nextPlacementTime || !isHoldingValidBlock(minecraft.thePlayer)) {
            return;
        }
        if (requireRightClick.isEnabled() && !Mouse.isButtonDown(1)) {
            return;
        }

        MovingObjectPosition hit = minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || hit.getBlockPos() == null || hit.sideHit == null) {
            return;
        }
        if (!CombatActionCoordinator.tryAcquire("LegitScaffold")) {
            return;
        }

        int useKey = minecraft.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.onTick(useKey);
        nextPlacementTime = now + 1000000000L / Math.max(1, placementCps.getValue());
        status = Status.PLACING;
    }

    private boolean isHoldingValidBlock(EntityPlayerSP player) {
        ItemStack stack = player.getHeldItem();
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return block != null && block.getMaterial() != Material.air && !block.getMaterial().isLiquid();
    }

    private void clearState(Minecraft minecraft) {
        sneakReleaseTime = 0L;
        nextPlacementTime = 0L;
        moduleSneaking = false;
        diagonalMovement = false;
        status = Status.READY;
        if (minecraft != null && minecraft.gameSettings != null) {
            int key = minecraft.gameSettings.keyBindSneak.getKeyCode();
            KeyBinding.setKeyBindState(key, isPhysicalKeyDown(key));
        }
    }

    private boolean isPhysicalKeyDown(int keyCode) {
        if (keyCode < 0) {
            int button = keyCode + 100;
            return button >= 0 && Mouse.isButtonDown(button);
        }
        return keyCode > 0 && Keyboard.isKeyDown(keyCode);
    }

    @Override
    public String getHudInfo() {
        return directionMode.getValue().displayName + " " + status.displayName;
    }

    private enum DirectionMode {
        ANY_DIRECTION("Any"),
        BACKWARD_ONLY("Backward"),
        BLOCKS_HELD("Blocks");

        private final String displayName;

        DirectionMode(String displayName) {
            this.displayName = displayName;
        }
    }

    private enum Status {
        READY("Ready"), EDGE("Edge"), DIAGONAL("Diagonal"), PLACING("Placing");

        private final String displayName;

        Status(String displayName) {
            this.displayName = displayName;
        }
    }
}
