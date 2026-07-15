package com.razorclient.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/** Immutable values captured at the start of one real Minecraft client tick. */
public final class TickContext {
    private final long sequence;
    private final long nanoTime;
    private final int playerTick;
    private final Minecraft minecraft;
    private final WorldClient world;
    private final EntityPlayerSP player;
    private final boolean focused;
    private final boolean guiOpen;
    private final boolean worldChanged;
    private final boolean playerChanged;
    private final float moveForward;
    private final float moveStrafe;
    private final boolean jumping;
    private final boolean sneaking;

    TickContext(long sequence, long nanoTime, Minecraft minecraft, WorldClient world,
            EntityPlayerSP player, boolean worldChanged, boolean playerChanged) {
        this.sequence = sequence;
        this.nanoTime = nanoTime;
        this.minecraft = minecraft;
        this.world = world;
        this.player = player;
        this.playerTick = player == null ? Integer.MIN_VALUE : player.ticksExisted;
        this.focused = minecraft != null && minecraft.inGameHasFocus;
        this.guiOpen = minecraft != null && minecraft.currentScreen != null;
        this.worldChanged = worldChanged;
        this.playerChanged = playerChanged;
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
    }

    public long getSequence() { return sequence; }
    public long getNanoTime() { return nanoTime; }
    public int getPlayerTick() { return playerTick; }
    public Minecraft getMinecraft() { return minecraft; }
    public WorldClient getWorld() { return world; }
    public EntityPlayerSP getPlayer() { return player; }
    public boolean isSessionAvailable() { return world != null && player != null; }
    public boolean isFocused() { return focused; }
    public boolean isGuiOpen() { return guiOpen; }
    public boolean isWorldChanged() { return worldChanged; }
    public boolean isPlayerChanged() { return playerChanged; }
    public float getMoveForward() { return moveForward; }
    public float getMoveStrafe() { return moveStrafe; }
    public boolean isJumping() { return jumping; }
    public boolean isSneaking() { return sneaking; }
}
