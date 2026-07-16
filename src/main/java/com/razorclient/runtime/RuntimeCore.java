package com.razorclient.runtime;

import com.razorclient.combat.TargetPublicationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/** Coordinates real-tick deduplication and shared immutable runtime snapshots. */
public final class RuntimeCore {
    private final ClientSession clientSession = new ClientSession();
    private final ResourceArbiter resourceArbiter = new ResourceArbiter();
    private final EntitySnapshotService entitySnapshots = new EntitySnapshotService();
    private final ModuleFaultBarrier faultBarrier = new ModuleFaultBarrier();
    private final TargetPublicationService targetPublications = new TargetPublicationService();
    private Object lastWorld;
    private Object lastPlayer;
    private int lastPlayerTick = Integer.MIN_VALUE;
    private long tickSequence;
    private long frameSequence;
    private volatile TickContext tickContext;
    private volatile FrameContext frameContext;

    public ClientSession.Observation observeSession(Minecraft minecraft) {
        return clientSession.observe(minecraft);
    }

    public synchronized boolean beginRealTick(Minecraft minecraft) {
        ClientSession.Observation observation = clientSession.observe(minecraft);
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
        try {
            clientSession.beginClientTick();
        } catch (Throwable failure) {
            faultBarrier.report(null, "client-thread-scheduler", failure);
            return false;
        }
        resourceArbiter.advanceTick(sequence);
        TickContext context = new TickContext(sequence, System.nanoTime(), minecraft, observation,
            worldChanged, playerChanged);
        tickContext = context;
        try {
            entitySnapshots.refresh(context, minecraft);
        } catch (Throwable failure) {
            entitySnapshots.clear();
            faultBarrier.report(null, "entity-snapshot-refresh", failure);
        }
        return true;
    }

    public synchronized FrameContext beginFrame(Minecraft minecraft, float partialTicks) {
        float value = Float.isFinite(partialTicks) ? Math.max(0.0F, Math.min(1.0F, partialTicks)) : 0.0F;
        frameContext = FrameContext.capture(++frameSequence, clientSession.getSessionGeneration(), value, minecraft);
        return frameContext;
    }

    public TickContext getTickContext() { return tickContext; }
    public FrameContext getFrameContext() { return frameContext; }
    public ResourceArbiter getResourceArbiter() { return resourceArbiter; }
    public EntitySnapshotService getEntitySnapshots() { return entitySnapshots; }
    public EntityFrame getEntityFrame() { return entitySnapshots.entityFrame(); }
    public ClientSession getClientSession() { return clientSession; }
    public ModuleFaultBarrier getFaultBarrier() { return faultBarrier; }
    public TargetPublicationService getTargetPublications() { return targetPublications; }

    public synchronized void reset() {
        resourceArbiter.clearAll();
        entitySnapshots.clear();
        faultBarrier.clear();
        targetPublications.clearAll();
        lastWorld = null;
        lastPlayer = null;
        lastPlayerTick = Integer.MIN_VALUE;
        tickContext = null;
        frameContext = null;
        clientSession.shutdown();
    }
}
