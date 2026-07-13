package com.razorclient.network;

import com.razorclient.feature.module.ModuleManager;
import com.razorclient.feature.module.ModuleManager.PacketDelaySelection;
import com.razorclient.inject.AgentLog;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

public final class PacketDelayManager {
    private static final int MAX_RELEASES_PER_TICK = 50;
    private static final int MAX_QUEUE_SIZE = 4096;

    private static volatile PacketDelayManager instance;

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final ModuleManager moduleManager;
    private final Queue<QueuedOutboundPacket> outboundQueue = new ArrayDeque<QueuedOutboundPacket>();
    private final Queue<QueuedInboundPacket> inboundQueue = new ArrayDeque<QueuedInboundPacket>();
    /** Packets detached from the active delay queue and awaiting client-thread delivery. */
    private final Queue<QueuedInboundPacket> pendingInboundReleases = new ArrayDeque<QueuedInboundPacket>();
    private final Set<Packet<?>> outboundFastTrack = Collections.newSetFromMap(
        Collections.synchronizedMap(new IdentityHashMap<Packet<?>, Boolean>())
    );
    private long lastOutboundReleaseAt;
    private long lastInboundReleaseAt;
    private String outboundQueueOwner = "None";
    private String inboundQueueOwner = "None";
    private volatile boolean inboundReleaseRequested;
    private long lastReleaseFailureAt;
    private long lastOverflowLogAt;
    private String lastFlushReason = "None";

    public PacketDelayManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
        instance = this;
    }

    public static PacketDelayManager getInstance() {
        return instance;
    }

    public void flushAll() {
        lastFlushReason = "Manual";
        flushQueuedInboundPackets();
        flushQueuedOutboundPackets();
    }

    public String getArbitrationStatus() {
        return "Out: " + outboundQueueOwner + " | In: " + inboundQueueOwner;
    }

    public String getQueueStatus() {
        return "Out " + outboundQueueSize() + " (" + outboundQueueOwner + ") | In "
            + inboundQueueSize() + " (" + inboundQueueOwner + ") | Flush " + lastFlushReason;
    }

    public int getApproximateQueuedDelay() {
        long now = monotonicMillis();
        long latest = now;
        synchronized (outboundQueue) {
            for (QueuedOutboundPacket packet : outboundQueue) {
                latest = Math.max(latest, packet.releaseAt);
            }
        }
        synchronized (inboundQueue) {
            for (QueuedInboundPacket packet : inboundQueue) {
                latest = Math.max(latest, packet.releaseAt);
            }
        }
        long remaining = Math.max(0L, latest - now);
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    public void onClientTick() {
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        if (netHandler == null) {
            lastFlushReason = "Disconnect";
            clearQueues();
            return;
        }

        boolean flushAllRequested = moduleManager.consumeFlushRequest();
        if (flushAllRequested || moduleManager.consumeOutboundFlushRequest()) {
            lastFlushReason = "Module request";
            flushQueuedOutboundPackets();
        }

        if (flushAllRequested || moduleManager.consumeInboundFlushRequest()) {
            lastFlushReason = "Module request";
            flushQueuedInboundPackets();
        }
        if (inboundReleaseRequested) {
            inboundReleaseRequested = false;
            lastFlushReason = "Owner change/overflow";
            flushPendingInboundPackets();
        }

        flushReadyInboundPackets();
        flushReadyOutboundPackets();
    }

    public boolean interceptOutbound(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>>[] listeners
    ) {
        if (consumeOutboundFastTrack(packet)) {
            return false;
        }

        moduleManager.onOutboundPacket(packet);
        PacketDelaySelection selection = moduleManager.selectOutboundPacketDelay(packet);
        int delay = selection.getDelay();
        if (delay <= 0 && !selection.isIndefinite()) {
            if (hasQueuedOutboundPackets()) {
                lastFlushReason = "Delay ended";
                flushQueuedOutboundPackets();
            }
            return false;
        }

        if (hasQueuedOutboundPackets() && !selection.getOwnerName().equals(outboundQueueOwner)) {
            lastFlushReason = "Owner changed";
            flushQueuedOutboundPackets();
        }
        if (outboundQueueSize() >= MAX_QUEUE_SIZE) {
            lastFlushReason = "Outbound overflow";
            flushQueuedOutboundPackets();
            logOverflow("outbound", selection.getOwnerName());
            if (selection.getOwner() != null) selection.getOwner().onPacketDelayOverflow(true);
        }
        synchronized (outboundQueue) {
            long now = monotonicMillis();
            long releaseAt = selection.isIndefinite() ? Long.MAX_VALUE : Math.max(now + delay, lastOutboundReleaseAt);
            lastOutboundReleaseAt = releaseAt;
            outboundQueue.add(new QueuedOutboundPacket(packet, listeners, releaseAt));
            outboundQueueOwner = selection.getOwnerName();
        }
        return true;
    }

    public boolean interceptInbound(Packet<?> packet, INetHandler listener) {
        if (listener == null) {
            return false;
        }

        moduleManager.onInboundPacket(packet);

        PacketDelaySelection selection = moduleManager.selectInboundPacketDelay(packet);
        int delay = selection.getDelay();
        if (delay <= 0 && !selection.isIndefinite()) {
            if (moduleManager.shouldCancelInboundPacket(packet)) {
                return true;
            }
            if (hasQueuedInboundPackets()) {
                lastFlushReason = "Delay ended";
                queueInboundForImmediateRelease(packet, listener);
                return true;
            }
            return false;
        }

        queueInbound(packet, listener, delay, selection);
        return true;
    }

    private void flushQueuedOutboundPackets() {
        List<QueuedOutboundPacket> packets = new ArrayList<QueuedOutboundPacket>();
        synchronized (outboundQueue) {
            while (!outboundQueue.isEmpty()) {
                packets.add(outboundQueue.poll());
            }
            lastOutboundReleaseAt = 0L;
            outboundQueueOwner = "None";
        }

        for (QueuedOutboundPacket packet : packets) {
            releaseOutbound(packet);
        }
    }

    private void flushQueuedInboundPackets() {
        synchronized (inboundQueue) {
            stageActiveInboundQueue();
            lastInboundReleaseAt = 0L;
            inboundQueueOwner = "None";
        }
        flushPendingInboundPackets();
    }

    private void flushReadyOutboundPackets() {
        long now = monotonicMillis();
        List<QueuedOutboundPacket> packets = new ArrayList<QueuedOutboundPacket>();
        synchronized (outboundQueue) {
            while (packets.size() < MAX_RELEASES_PER_TICK
                && !outboundQueue.isEmpty()
                && outboundQueue.peek().releaseAt <= now) {
                packets.add(outboundQueue.poll());
            }
            if (outboundQueue.isEmpty()) {
                lastOutboundReleaseAt = 0L;
                outboundQueueOwner = "None";
            }
        }

        for (QueuedOutboundPacket packet : packets) {
            releaseOutbound(packet);
        }
    }

    private void flushReadyInboundPackets() {
        flushPendingInboundPackets();
        long now = monotonicMillis();
        List<QueuedInboundPacket> packets = new ArrayList<QueuedInboundPacket>();
        synchronized (inboundQueue) {
            while (packets.size() < MAX_RELEASES_PER_TICK
                && !inboundQueue.isEmpty()
                && inboundQueue.peek().releaseAt <= now) {
                packets.add(inboundQueue.poll());
            }
            if (inboundQueue.isEmpty()) {
                lastInboundReleaseAt = 0L;
                inboundQueueOwner = "None";
            }
        }

        for (QueuedInboundPacket packet : packets) {
            releaseInbound(packet);
        }
    }

    private void releaseOutbound(QueuedOutboundPacket queuedPacket) {
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        if (netHandler == null) {
            return;
        }

        if (netHandler.getNetworkManager() == null) {
            return;
        }

        outboundFastTrack.add(queuedPacket.packet);
        try {
            netHandler.getNetworkManager().dispatchPacket(
                queuedPacket.packet,
                queuedPacket.listeners
            );
        } catch (Exception failure) {
            logReleaseFailure("outbound", failure);
        } finally {
            outboundFastTrack.remove(queuedPacket.packet);
        }
    }

    private void releaseInbound(final QueuedInboundPacket queuedPacket) {
        moduleManager.onInboundPacketReleased(queuedPacket.packet);
        if (moduleManager.shouldCancelInboundPacket(queuedPacket.packet)) {
            return;
        }
        try {
            queuedPacket.action.run();
        } catch (Exception failure) {
            logReleaseFailure("inbound", failure);
        }
    }

    private boolean consumeOutboundFastTrack(Packet<?> packet) {
        return outboundFastTrack.remove(packet);
    }

    @SuppressWarnings("unchecked")
    private Runnable createInboundAction(final Packet<?> packet, final INetHandler listener) {
        final Packet<INetHandler> typedPacket = (Packet<INetHandler>) packet;
        return new Runnable() {
            @Override
            public void run() {
                typedPacket.processPacket(listener);
            }
        };
    }

    private void clearQueues() {
        synchronized (outboundQueue) {
            outboundQueue.clear();
        }
        synchronized (inboundQueue) {
            inboundQueue.clear();
            pendingInboundReleases.clear();
        }
        lastOutboundReleaseAt = 0L;
        lastInboundReleaseAt = 0L;
        outboundQueueOwner = "None";
        inboundQueueOwner = "None";
        inboundReleaseRequested = false;
        outboundFastTrack.clear();
    }

    private boolean hasQueuedOutboundPackets() {
        synchronized (outboundQueue) {
            return !outboundQueue.isEmpty();
        }
    }

    private boolean hasQueuedInboundPackets() {
        synchronized (inboundQueue) {
            return !inboundQueue.isEmpty() || !pendingInboundReleases.isEmpty();
        }
    }

    private int outboundQueueSize() {
        synchronized (outboundQueue) {
            return outboundQueue.size();
        }
    }

    private int inboundQueueSize() {
        synchronized (inboundQueue) {
            return inboundQueue.size() + pendingInboundReleases.size();
        }
    }

    private void queueInbound(
        Packet<?> packet,
        INetHandler listener,
        int delay,
        PacketDelaySelection selection
    ) {
        String owner = selection.getOwnerName();
        Runnable action = createInboundAction(packet, listener);
        synchronized (inboundQueue) {
            if (!inboundQueue.isEmpty() && !owner.equals(inboundQueueOwner)) {
                stageActiveInboundQueue();
                inboundReleaseRequested = true;
            }
            if (inboundQueue.size() >= MAX_QUEUE_SIZE) {
                // Packet processing belongs to the client thread. Request an ordered flush there.
                stageActiveInboundQueue();
                inboundReleaseRequested = true;
                logOverflow("inbound", owner);
                if (selection.getOwner() != null) selection.getOwner().onPacketDelayOverflow(false);
            }
            long now = monotonicMillis();
            long releaseAt = selection.isIndefinite() ? Long.MAX_VALUE : Math.max(now + delay, lastInboundReleaseAt);
            lastInboundReleaseAt = releaseAt;
            inboundQueue.add(new QueuedInboundPacket(packet, action, releaseAt));
            if (inboundQueue.size() == 1) {
                inboundQueueOwner = owner;
            }
        }
        moduleManager.onInboundPacketQueued(packet);
    }

    private void queueInboundForImmediateRelease(Packet<?> packet, INetHandler listener) {
        QueuedInboundPacket queued = new QueuedInboundPacket(packet, createInboundAction(packet, listener), monotonicMillis());
        synchronized (inboundQueue) {
            stageActiveInboundQueue();
            pendingInboundReleases.add(queued);
            lastInboundReleaseAt = 0L;
            inboundQueueOwner = "None";
            inboundReleaseRequested = true;
        }
        moduleManager.onInboundPacketQueued(packet);
    }

    /** Caller must hold the inboundQueue monitor. */
    private void stageActiveInboundQueue() {
        while (!inboundQueue.isEmpty()) {
            pendingInboundReleases.add(inboundQueue.poll());
        }
        lastInboundReleaseAt = 0L;
        inboundQueueOwner = "None";
    }

    private void flushPendingInboundPackets() {
        List<QueuedInboundPacket> packets = new ArrayList<QueuedInboundPacket>();
        synchronized (inboundQueue) {
            while (!pendingInboundReleases.isEmpty()) {
                packets.add(pendingInboundReleases.poll());
            }
        }
        for (QueuedInboundPacket packet : packets) {
            releaseInbound(packet);
        }
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private void logReleaseFailure(String direction, Exception failure) {
        long now = monotonicMillis();
        if (now - lastReleaseFailureAt < 5_000L) {
            return;
        }
        lastReleaseFailureAt = now;
        AgentLog.error("Unable to release delayed " + direction + " packet", failure);
    }

    private void logOverflow(String direction, String owner) {
        long now = monotonicMillis();
        if (now - lastOverflowLogAt < 5_000L) return;
        lastOverflowLogAt = now;
        AgentLog.info("Ordered " + direction + " queue overflow flush; owner=" + owner);
    }

    private static final class QueuedOutboundPacket {
        private final Packet<?> packet;
        private final GenericFutureListener<? extends Future<? super Void>>[] listeners;
        private final long releaseAt;

        private QueuedOutboundPacket(
            Packet<?> packet,
            GenericFutureListener<? extends Future<? super Void>>[] listeners,
            long releaseAt
        ) {
            this.packet = packet;
            this.listeners = listeners;
            this.releaseAt = releaseAt;
        }
    }

    private static final class QueuedInboundPacket {
        private final Packet<?> packet;
        private final Runnable action;
        private final long releaseAt;

        private QueuedInboundPacket(Packet<?> packet, Runnable action, long releaseAt) {
            this.packet = packet;
            this.action = action;
            this.releaseAt = releaseAt;
        }
    }
}
