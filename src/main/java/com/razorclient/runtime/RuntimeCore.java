package com.razorclient.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/** Coordinates real-tick deduplication and shared immutable runtime snapshots. */
public final class RuntimeCore {
    private final ResourceArbiter resourceArbiter = new ResourceArbiter();
    private final EntitySnapshotService entitySnapshots = new EntitySnapshotService();
    private final ModuleFaultBarrier faultBarrier = new ModuleFaultBarrier();
    private Object lastWorld;
    private Object lastPlayer;
    private int lastPlayerTick = Integer.MIN_VALUE;
    private long tickSequence;
    private long frameSequence;
    private volatile TickContext tickContext;
    private volatile FrameContext frameContext;

    public synchronized boolean beginRealTick(Minecraft minecraft) {
        WorldClient world = minecraft == null ? null : minecraft.theWorld;
        EntityPlayerSP player = minecraft == null ? null : minecraft.thePlayer;
        int playerTick = player == null ? Integer.MIN_VALUE : player.ticksExisted;
        boolean identityChanged = world != lastWorld || player != lastPlayer;
        if (!identityChanged && playerTick == lastPlayerTick) return false;

        boolean worldChanged = world != lastWorld;
        boolean playerChanged = player != lastPlayer;
        lastWorld = world;
        lastPlayer = player;
        lastPlayerTick = playerTick;
        long sequence = ++tickSequence;
        resourceArbiter.advanceTick(sequence);
        TickContext context = new TickContext(sequence, System.nanoTime(), minecraft, world, player,
            worldChanged, playerChanged);
        tickContext = context;
        try {
            entitySnapshots.refresh(context);
        } catch (Throwable failure) {
            entitySnapshots.clear();
            faultBarrier.report(null, "entity-snapshot-refresh", failure);
        }
        return true;
    }

    public synchronized FrameContext beginFrame(Minecraft minecraft, float partialTicks) {
        float value = Float.isFinite(partialTicks) ? Math.max(0.0F, Math.min(1.0F, partialTicks)) : 0.0F;
        frameContext = FrameContext.capture(++frameSequence, value, minecraft);
        return frameContext;
    }

    public TickContext getTickContext() { return tickContext; }
    public FrameContext getFrameContext() { return frameContext; }
    public ResourceArbiter getResourceArbiter() { return resourceArbiter; }
    public EntitySnapshotService getEntitySnapshots() { return entitySnapshots; }
    public ModuleFaultBarrier getFaultBarrier() { return faultBarrier; }

    public synchronized void reset() {
        resourceArbiter.clearAll();
        entitySnapshots.clear();
        faultBarrier.clear();
        lastWorld = null;
        lastPlayer = null;
        lastPlayerTick = Integer.MIN_VALUE;
        tickContext = null;
        frameContext = null;
    }
}
