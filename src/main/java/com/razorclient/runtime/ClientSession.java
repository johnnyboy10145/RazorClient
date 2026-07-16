package com.razorclient.runtime;

import com.razorclient.feature.module.ModuleResetReason;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/** Instance-owned lifecycle state for one injected RazorClient runtime. */
public final class ClientSession {
    public enum State { STARTING, ACTIVE, SUSPENDED, STOPPING, TERMINATED }

    private final ClientThreadScheduler scheduler = new ClientThreadScheduler();
    private Object worldIdentity;
    private Object playerIdentity;
    private boolean playerDead;
    private boolean inputUnavailable = true;
    private long sessionGeneration = 1L;
    private long worldGeneration;
    private long playerGeneration;
    private State state = State.STARTING;

    public synchronized Observation observe(Minecraft minecraft) {
        if (state == State.STOPPING || state == State.TERMINATED) return Observation.TERMINATED;

        WorldClient world = minecraft == null ? null : minecraft.theWorld;
        EntityPlayerSP player = minecraft == null ? null : minecraft.thePlayer;
        boolean dead = player != null && player.isDead;
        ModuleResetReason resetReason = null;
        boolean sessionChanged = false;

        if (world != worldIdentity || player != playerIdentity) {
            resetReason = world == null ? ModuleResetReason.DISCONNECT
                : world == worldIdentity ? ModuleResetReason.RESPAWN : ModuleResetReason.WORLD_CHANGE;
            if (world != worldIdentity) worldGeneration++;
            if (player != playerIdentity) playerGeneration++;
            worldIdentity = world;
            playerIdentity = player;
            playerDead = dead;
            sessionGeneration++;
            scheduler.advanceSession();
            sessionChanged = true;
        } else if (dead != playerDead) {
            playerDead = dead;
            playerGeneration++;
            sessionGeneration++;
            scheduler.advanceSession();
            resetReason = ModuleResetReason.RESPAWN;
            sessionChanged = true;
        }

        boolean unavailable = minecraft == null || world == null || player == null || dead
            || !minecraft.inGameHasFocus || minecraft.currentScreen != null;
        boolean inputLost = unavailable && !inputUnavailable;
        boolean inputRestored = !unavailable && inputUnavailable;
        inputUnavailable = unavailable;
        state = world == null || player == null || dead ? State.SUSPENDED : State.ACTIVE;
        ModuleResetReason inputReason = inputLost
            ? minecraft != null && minecraft.currentScreen != null
                ? ModuleResetReason.GUI_OPENED : ModuleResetReason.FOCUS_LOSS
            : null;
        return new Observation(state, resetReason, inputReason, sessionChanged, inputLost,
            inputRestored, sessionGeneration, worldGeneration, playerGeneration);
    }

    public void beginClientTick() {
        scheduler.bindAndDrain();
    }

    public synchronized State getState() { return state; }
    public synchronized long getSessionGeneration() { return sessionGeneration; }
    public synchronized long getWorldGeneration() { return worldGeneration; }
    public synchronized long getPlayerGeneration() { return playerGeneration; }
    public ClientThreadScheduler getScheduler() { return scheduler; }

    public synchronized void shutdown() {
        if (state == State.TERMINATED) return;
        state = State.STOPPING;
        scheduler.close();
        worldIdentity = null;
        playerIdentity = null;
        state = State.TERMINATED;
    }

    public static final class Observation {
        private static final Observation TERMINATED = new Observation(State.TERMINATED, null, null,
            false, false, false, 0L, 0L, 0L);
        private final State state;
        private final ModuleResetReason resetReason;
        private final ModuleResetReason inputReason;
        private final boolean sessionChanged;
        private final boolean inputLost;
        private final boolean inputRestored;
        private final long sessionGeneration;
        private final long worldGeneration;
        private final long playerGeneration;

        private Observation(State state, ModuleResetReason resetReason, ModuleResetReason inputReason,
                boolean sessionChanged, boolean inputLost, boolean inputRestored, long sessionGeneration,
                long worldGeneration, long playerGeneration) {
            this.state = state;
            this.resetReason = resetReason;
            this.inputReason = inputReason;
            this.sessionChanged = sessionChanged;
            this.inputLost = inputLost;
            this.inputRestored = inputRestored;
            this.sessionGeneration = sessionGeneration;
            this.worldGeneration = worldGeneration;
            this.playerGeneration = playerGeneration;
        }

        public State getState() { return state; }
        public ModuleResetReason getResetReason() { return resetReason; }
        public ModuleResetReason getInputReason() { return inputReason; }
        public boolean isSessionChanged() { return sessionChanged; }
        public boolean isInputLost() { return inputLost; }
        public boolean isInputRestored() { return inputRestored; }
        public long getSessionGeneration() { return sessionGeneration; }
        public long getWorldGeneration() { return worldGeneration; }
        public long getPlayerGeneration() { return playerGeneration; }
    }
}
