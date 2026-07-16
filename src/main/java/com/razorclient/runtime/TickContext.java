package com.razorclient.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/** Immutable scalar values captured at the start of one real Minecraft client tick. */
public final class TickContext {
    private final long sequence;
    private final long nanoTime;
    private final long sessionGeneration;
    private final long worldGeneration;
    private final long playerGeneration;
    private final int playerEntityId;
    private final int playerTick;
    private final boolean sessionAvailable;
    private final boolean focused;
    private final boolean guiOpen;
    private final boolean worldChanged;
    private final boolean playerChanged;
    private final boolean playerDead;
    private final boolean onGround;
    private final float moveForward;
    private final float moveStrafe;
    private final boolean jumping;
    private final boolean sneaking;
    private final double x;
    private final double y;
    private final double z;
    private final double motionX;
    private final double motionY;
    private final double motionZ;
    private final float yaw;
    private final float pitch;

    TickContext(long sequence, long nanoTime, Minecraft minecraft, ClientSession.Observation observation,
            boolean worldChanged, boolean playerChanged) {
        EntityPlayerSP player = minecraft == null ? null : minecraft.thePlayer;
        this.sequence = sequence;
        this.nanoTime = nanoTime;
        this.sessionGeneration = observation.getSessionGeneration();
        this.worldGeneration = observation.getWorldGeneration();
        this.playerGeneration = observation.getPlayerGeneration();
        this.playerEntityId = player == null ? -1 : player.getEntityId();
        this.playerTick = player == null ? Integer.MIN_VALUE : player.ticksExisted;
        this.sessionAvailable = minecraft != null && minecraft.theWorld != null && player != null;
        this.focused = minecraft != null && minecraft.inGameHasFocus;
        this.guiOpen = minecraft != null && minecraft.currentScreen != null;
        this.worldChanged = worldChanged;
        this.playerChanged = playerChanged;
        this.playerDead = player == null || player.isDead;
        this.onGround = player != null && player.onGround;
        if (player == null || player.movementInput == null) {
            this.moveForward = 0.0F;
            this.moveStrafe = 0.0F;
            this.jumping = false;
            this.sneaking = false;
        } else {
            this.moveForward = player.movementInput.moveForward;
            this.moveStrafe = player.movementInput.moveStrafe;
            this.jumping = player.movementInput.jump;
            this.sneaking = player.movementInput.sneak;
        }
        this.x = player == null ? 0.0D : player.posX;
        this.y = player == null ? 0.0D : player.posY;
        this.z = player == null ? 0.0D : player.posZ;
        this.motionX = player == null ? 0.0D : player.motionX;
        this.motionY = player == null ? 0.0D : player.motionY;
        this.motionZ = player == null ? 0.0D : player.motionZ;
        this.yaw = player == null ? 0.0F : player.rotationYaw;
        this.pitch = player == null ? 0.0F : player.rotationPitch;
    }

    public long getSequence() { return sequence; }
    public long getNanoTime() { return nanoTime; }
    public long getSessionGeneration() { return sessionGeneration; }
    public long getWorldGeneration() { return worldGeneration; }
    public long getPlayerGeneration() { return playerGeneration; }
    public int getPlayerEntityId() { return playerEntityId; }
    public int getPlayerTick() { return playerTick; }
    public boolean isSessionAvailable() { return sessionAvailable; }
    public boolean isFocused() { return focused; }
    public boolean isGuiOpen() { return guiOpen; }
    public boolean isWorldChanged() { return worldChanged; }
    public boolean isPlayerChanged() { return playerChanged; }
    public boolean isPlayerDead() { return playerDead; }
    public boolean isOnGround() { return onGround; }
    public float getMoveForward() { return moveForward; }
    public float getMoveStrafe() { return moveStrafe; }
    public boolean isJumping() { return jumping; }
    public boolean isSneaking() { return sneaking; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getMotionX() { return motionX; }
    public double getMotionY() { return motionY; }
    public double getMotionZ() { return motionZ; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
}
