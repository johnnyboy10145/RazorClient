package com.razorclient.network;

import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.ModuleManager;
import com.razorclient.network.PacketDecision;
import com.razorclient.network.PacketReleasePolicy;
import com.razorclient.inject.AgentLog;
import com.razorclient.runtime.OwnerToken;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;

/**
 * Single ordered packet transport shared by delay modules.
 *
 * <p>Packets retain their owning module identity, so a module can release only
 * its own lane. The global deques retain interception order across lanes. A
 * ready packet never overtakes an earlier held packet.</p>
 */
public final class PacketDelayManager {
    private static final int MAX_OUTBOUND_RELEASES_PER_TICK = 8;
    private static final int MAX_INBOUND_RELEASES_PER_TICK = 32;
    private static final long MAX_INBOUND_RELEASE_NANOS = 2_000_000L;
    private static final int MAX_OUTBOUND_QUEUE_SIZE = 512;
    private static final int OUTBOUND_OVERFLOW_HIGH_WATER = 448;
    private static final int OUTBOUND_RECOVERY_LOW_WATER = 256;
    private static final int MAX_INBOUND_QUEUE_SIZE = 4096;
    private static final int INBOUND_OVERFLOW_HIGH_WATER = 3584;
    private static final int INBOUND_RESUME_QUEUE_SIZE = 2048;
    private static final int INBOUND_RECOVERY_LOW_WATER = 1024;
    private static final int OUTBOUND_OWNER_HIGH_WATER = 224;
    private static final int INBOUND_OWNER_HIGH_WATER = 1792;
    private static final long FAST_TRACK_TTL_MS = 10_000L;

    private static volatile PacketDelayManager instance;

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final ModuleManager moduleManager;
    private final Queue<QueuedOutboundPacket> outboundQueue = new ArrayDeque<QueuedOutboundPacket>();
    private final Queue<QueuedInboundPacket> inboundQueue = new ArrayDeque<QueuedInboundPacket>();
    private final List<QueuedOutboundPacket> outboundReleaseBatch =
        new ArrayList<QueuedOutboundPacket>(MAX_OUTBOUND_RELEASES_PER_TICK);
    private final Set<String> blockedOutboundDomains = new HashSet<String>();
    private final Set<String> blockedInboundDomains = new HashSet<String>();
    private final Map<Packet<?>, Long> outboundFastTrack = Collections.synchronizedMap(
        new IdentityHashMap<Packet<?>, Long>()
    );
    private final Object connectionLock = new Object();

    private long lastReleaseFailureAt;
    private long lastOverflowLogAt;
    private String lastFlushReason = "None";
    private volatile Channel pausedInboundChannel;
    private volatile NetworkManager activeNetworkManager;
    private volatile boolean outboundRecovery;
    private volatile boolean inboundRecovery;
    private volatile boolean closed;

    public PacketDelayManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
        instance = this;
    }

    public static PacketDelayManager getInstance() {
        return instance;
    }

    /** True while this exact packet is re-entering Netty for its one allowed release write. */
    public boolean isReleasingOutbound(Packet<?> packet) {
        return packet != null && outboundFastTrack.containsKey(packet);
    }

    /** Destructive unload policy: stop interception and fail/drop queued work without a burst. */
    public void closeForUnload() {
        if (closed) return;
        closed = true;
        lastFlushReason = "Unload";
        clearQueues(new ClosedChannelException());
        activeNetworkManager = null;
        if (instance == this) instance = null;
    }

    public String getArbitrationStatus() {
        return "Out: " + ownerSummary(outboundQueue) + " | In: " + ownerSummary(inboundQueue);
    }

    public String getQueueStatus() {
        return "Out " + outboundQueueSize() + " (" + ownerSummary(outboundQueue) + ") | In "
            + inboundQueueSize() + " (" + ownerSummary(inboundQueue) + ") | Flush " + lastFlushReason;
    }

    public int getApproximateQueuedDelay() {
        long now = monotonicMillis();
        long latest = now;
        synchronized (outboundQueue) {
            for (QueuedOutboundPacket packet : outboundQueue) {
                if (packet.indefinite) return Integer.MAX_VALUE;
                latest = Math.max(latest, packet.releaseAt);
            }
        }
        synchronized (inboundQueue) {
            for (QueuedInboundPacket packet : inboundQueue) {
                if (packet.indefinite) return Integer.MAX_VALUE;
                latest = Math.max(latest, packet.releaseAt);
            }
        }
        long remaining = Math.max(0L, latest - now);
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    public void onClientTick() {
        if (closed) return;
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        NetworkManager currentManager = netHandler == null ? null : netHandler.getNetworkManager();
        synchronized (connectionLock) {
            if (currentManager == null) {
                lastFlushReason = "Disconnect";
                clearQueues(new ClosedChannelException());
                activeNetworkManager = null;
                return;
            }
            if (activeNetworkManager != currentManager) {
                lastFlushReason = activeNetworkManager == null ? "Connection initialized" : "Connection changed";
                clearQueues(new ClosedChannelException());
                activeNetworkManager = currentManager;
            }
        }

        discardStaleConnections(currentManager);
        consumeOwnerFlushRequests();
        releaseInactiveOwners();
        cleanupFastTrack();
        drainReadyInbound(MAX_INBOUND_RELEASES_PER_TICK, MAX_INBOUND_RELEASE_NANOS);
        drainReadyOutbound(MAX_OUTBOUND_RELEASES_PER_TICK);
        resumeInboundIfDrained();
    }

    public boolean interceptOutbound(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>>[] listeners
    ) {
        return interceptOutbound(packet, listeners, null);
    }

    /**
     * Live Netty entrypoint. The original promise is completed by the future
     * from the eventual write, never at queue time.
     */
    public boolean interceptOutbound(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>>[] listeners,
        ChannelPromise originalPromise
    ) {
        return interceptOutbound(packet, listeners, originalPromise, currentNetworkManager());
    }

    public boolean interceptOutbound(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>>[] listeners,
        ChannelPromise originalPromise,
        NetworkManager sourceManager
    ) {
        if (closed || packet == null || consumeOutboundFastTrack(packet)) {
            return false;
        }
        NetworkManager connection = sourceManager;
        if (connection == null || connection != currentNetworkManager()) {
            if (originalPromise != null) originalPromise.tryFailure(new ClosedChannelException());
            return true;
        }
        bindConnection(connection);

        PacketDecision decision = moduleManager.captureOutboundPacketDecision(packet);
        boolean requestedDelay = decision.isHeld();
        boolean flushThenPass = decision.getAction() == PacketDecision.Action.FLUSH_THEN_PASS;
        Module owner = decision.getOwner();
        OwnerToken ownerToken = decision.getOwnerToken();
        if (requestedDelay && (owner == null || ownerToken == null)) return false;

        boolean overflow = false;
        boolean rejected = false;
        boolean orderedBarrier = flushThenPass;
        Throwable rejectionFailure = null;
        List<QueuedOutboundPacket> immediateRecovery = null;
        OutboundCompletion completion = new OutboundCompletion(listeners, originalPromise);
        synchronized (outboundQueue) {
            if (closed) {
                rejected = true;
                rejectionFailure = new ClosedChannelException();
            }
            if (outboundRecovery && outboundQueue.size() <= OUTBOUND_RECOVERY_LOW_WATER) {
                outboundRecovery = false;
            }
            if (!rejected && flushThenPass && ownerToken != null) {
                compactMovementRuns(owner, ownerToken);
                markOwnerReady(outboundQueue, ownerToken);
                lastFlushReason = "Flush then pass: " + owner.getName();
            }
            if (!rejected && !requestedDelay && outboundQueue.isEmpty()) return false;
            if (!rejected && !requestedDelay && !flushThenPass
                    && canBypassOutboundOrdering(outboundQueue, packet)) return false;
            if (!rejected && owner != null && (countOwner(outboundQueue, ownerToken)
                    >= OUTBOUND_OWNER_HIGH_WATER || outboundQueue.size() >= OUTBOUND_OVERFLOW_HIGH_WATER)) {
                compactMovementRuns(owner, ownerToken);
            }
            if (!rejected && outboundQueue.size() >= OUTBOUND_OVERFLOW_HIGH_WATER) {
                Set<OwnerToken> lanes = new LinkedHashSet<OwnerToken>();
                for (QueuedOutboundPacket queued : outboundQueue) {
                    if (queued.ownerToken != null) lanes.add(queued.ownerToken);
                }
                for (OwnerToken token : lanes) {
                    compactMovementRuns(findOwner(outboundQueue, token), token);
                }
            }
            int ownerQueueSize = owner == null ? 0 : countOwner(outboundQueue, ownerToken);
            if (!rejected && owner != null && ownerQueueSize >= OUTBOUND_OWNER_HIGH_WATER) {
                markOwnerReady(outboundQueue, ownerToken);
                lastFlushReason = "Outbound owner overflow: " + owner.getName();
                outboundRecovery = true;
                overflow = true;
            }
            if (!rejected && outboundQueue.size() >= OUTBOUND_OVERFLOW_HIGH_WATER) {
                markAllReadyLocked(outboundQueue);
                lastFlushReason = "Outbound global overflow";
                outboundRecovery = true;
                overflow = true;
            }
            if (!rejected && outboundRecovery && requestedDelay) {
                requestedDelay = false;
                orderedBarrier = true;
            }
            if (!rejected && outboundQueue.size() >= MAX_OUTBOUND_QUEUE_SIZE) {
                markAllReadyLocked(outboundQueue);
                lastFlushReason = "Outbound hard-cap recovery";
                outboundRecovery = true;
                overflow = true;
                immediateRecovery = new ArrayList<QueuedOutboundPacket>(MAX_OUTBOUND_RELEASES_PER_TICK);
                pollReadyOutboundLocked(monotonicMillis(), MAX_OUTBOUND_RELEASES_PER_TICK, immediateRecovery);
            }
            if (!rejected && outboundQueue.size() >= MAX_OUTBOUND_QUEUE_SIZE) {
                requestedDelay = false;
                orderedBarrier = true;
                if (!canBypassOutboundOrdering(outboundQueue, packet)) {
                    rejected = true;
                    rejectionFailure = new IllegalStateException("Outbound recovery queue saturated");
                }
            }
            if (!rejected) {
                long now = monotonicMillis();
                outboundQueue.add(new QueuedOutboundPacket(
                    packet,
                    owner,
                    ownerToken,
                    connection,
                    requestedDelay ? deadline(now, decision) : 0L,
                    requestedDelay && decision.getReleasePolicy() == PacketReleasePolicy.EXPLICIT_FLUSH,
                    completion,
                    decision.getLane(),
                    orderedBarrier || !requestedDelay
                ));
            }
        }
        if (overflow) {
            String ownerName = owner == null ? "Transport" : owner.getName();
            logOverflow("outbound", ownerName);
            moduleManager.onPacketDelayOverflow(owner, true);
        }
        if (immediateRecovery != null) {
            for (int index = 0; index < immediateRecovery.size(); index++) {
                releaseOutbound(immediateRecovery.get(index));
            }
        }
        if (rejected) completion.fail(rejectionFailure == null
            ? new IllegalStateException("Outbound packet rejected") : rejectionFailure, connection);
        return !rejected;
    }

    public boolean interceptInbound(Packet<?> packet, INetHandler listener) {
        return interceptInbound(packet, listener, null);
    }

    public boolean interceptInbound(
        Packet<?> packet,
        INetHandler listener,
        ChannelHandlerContext context
    ) {
        return interceptInbound(packet, listener, context, currentNetworkManager());
    }

    public boolean interceptInbound(
        Packet<?> packet,
        INetHandler listener,
        ChannelHandlerContext context,
        NetworkManager sourceManager
    ) {
        if (closed || packet == null || listener == null) {
            return false;
        }
        NetworkManager connection = sourceManager;
        if (connection == null || connection != currentNetworkManager()) return true;
        bindConnection(connection);

        PacketDecision decision = moduleManager.captureInboundPacketDecision(packet);
        boolean requestedDelay = decision.isHeld();
        if (!requestedDelay && decision.isCancelled()) return true;
        Module owner = decision.getOwner();
        OwnerToken ownerToken = decision.getOwnerToken();
        if (requestedDelay && (owner == null || ownerToken == null)) return false;

        boolean overflow = false;
        boolean rejected = false;
        boolean orderedBarrier = false;
        List<QueuedInboundPacket> immediateRecovery = null;
        synchronized (inboundQueue) {
            if (closed) return true;
            if (inboundRecovery && inboundQueue.size() <= INBOUND_RECOVERY_LOW_WATER) {
                inboundRecovery = false;
            }
            if (!requestedDelay && inboundQueue.isEmpty()) return false;
            int ownerQueueSize = owner == null ? 0 : countOwner(inboundQueue, ownerToken);
            if (owner != null && ownerQueueSize >= INBOUND_OWNER_HIGH_WATER) {
                markOwnerReady(inboundQueue, ownerToken);
                lastFlushReason = "Inbound owner overflow: " + owner.getName();
                inboundRecovery = true;
                overflow = true;
            }
            if (inboundQueue.size() >= INBOUND_OVERFLOW_HIGH_WATER) {
                markAllReadyLocked(inboundQueue);
                lastFlushReason = "Inbound global overflow";
                inboundRecovery = true;
                overflow = true;
                pauseInbound(context);
            }
            if (inboundRecovery && requestedDelay) {
                requestedDelay = false;
                orderedBarrier = true;
            }
            if (inboundQueue.size() >= MAX_INBOUND_QUEUE_SIZE) {
                markAllReadyLocked(inboundQueue);
                lastFlushReason = "Inbound hard-cap recovery";
                inboundRecovery = true;
                overflow = true;
                immediateRecovery = new ArrayList<QueuedInboundPacket>(MAX_INBOUND_RELEASES_PER_TICK);
                for (int index = 0; index < MAX_INBOUND_RELEASES_PER_TICK; index++) {
                    QueuedInboundPacket ready = pollReadyInboundLocked(monotonicMillis());
                    if (ready == null) break;
                    immediateRecovery.add(ready);
                }
            }
            if (inboundQueue.size() >= MAX_INBOUND_QUEUE_SIZE) {
                requestedDelay = false;
                orderedBarrier = true;
                if (!canBypassInboundOrdering(inboundQueue, decision.getLane())) rejected = true;
            }
            if (!rejected) {
                long now = monotonicMillis();
                inboundQueue.add(new QueuedInboundPacket(
                    packet,
                    owner,
                    ownerToken,
                    connection,
                    requestedDelay ? deadline(now, decision) : 0L,
                    requestedDelay && decision.getReleasePolicy() == PacketReleasePolicy.EXPLICIT_FLUSH,
                    createInboundAction(packet, listener),
                    decision.shouldCancelOnRelease(),
                    decision.getLane(),
                    orderedBarrier
                ));
                if (inboundQueue.size() >= INBOUND_RESUME_QUEUE_SIZE) pauseInbound(context);
            }
        }
        if (!rejected) moduleManager.onInboundPacketQueued(packet);
        if (overflow) {
            String ownerName = owner == null ? "Transport" : owner.getName();
            logOverflow("inbound", ownerName);
            moduleManager.onPacketDelayOverflow(owner, false);
        }
        if (immediateRecovery != null) {
            for (int index = 0; index < immediateRecovery.size(); index++) {
                releaseInbound(immediateRecovery.get(index));
            }
        }
        return !rejected;
    }

    private void pauseInbound(ChannelHandlerContext context) {
        if (context == null || context.channel() == null) return;
        Channel channel = context.channel();
        if (!channel.config().isAutoRead()) return;
        channel.config().setAutoRead(false);
        pausedInboundChannel = channel;
    }

    private void resumeInboundIfDrained() {
        final Channel channel = pausedInboundChannel;
        int queued = inboundQueueSize();
        if (queued <= INBOUND_RECOVERY_LOW_WATER) inboundRecovery = false;
        if (channel == null || queued > INBOUND_RESUME_QUEUE_SIZE) return;
        pausedInboundChannel = null;
        setAutoReadAsync(channel, true);
    }

    private void consumeOwnerFlushRequests() {
        for (int index = 0; index < moduleManager.getPacketPolicyModuleCount(); index++) {
            Module module = moduleManager.getPacketPolicyModule(index);
            int requests = moduleManager.consumePacketFlushRequests(module);
            boolean both = (requests & 1) != 0;
            boolean outbound = (requests & 2) != 0;
            boolean inbound = (requests & 4) != 0;
            OwnerToken token = module.getScope().getOwnerToken();
            if (both || outbound) {
                synchronized (outboundQueue) {
                    compactMovementRuns(module, token);
                    markOwnerReady(outboundQueue, token);
                }
            }
            if (both || inbound) markOwnerReady(inboundQueue, token);
            if (both || outbound || inbound) {
                lastFlushReason = "Owner request: " + module.getName();
            }
        }
    }

    private void releaseInactiveOwners() {
        synchronized (outboundQueue) {
            for (QueuedOutboundPacket packet : outboundQueue) {
                if (packet.owner != null && (!packet.owner.isEnabled()
                        || !packet.ownerToken.equals(packet.owner.getScope().getOwnerToken())
                        || (packet.indefinite && !moduleManager.isPacketDelayOwnerActive(packet.owner, true)))) {
                    packet.makeReady();
                }
            }
        }
        synchronized (inboundQueue) {
            for (QueuedInboundPacket packet : inboundQueue) {
                if (packet.owner != null && (!packet.owner.isEnabled()
                        || !packet.ownerToken.equals(packet.owner.getScope().getOwnerToken())
                        || (packet.indefinite && !moduleManager.isPacketDelayOwnerActive(packet.owner, false)))) {
                    packet.makeReady();
                }
            }
        }
    }

    private void drainReadyOutbound(int limit) {
        outboundReleaseBatch.clear();
        synchronized (outboundQueue) {
            pollReadyOutboundLocked(monotonicMillis(), limit, outboundReleaseBatch);
            if (outboundQueue.size() <= OUTBOUND_RECOVERY_LOW_WATER) outboundRecovery = false;
        }
        for (int index = 0; index < outboundReleaseBatch.size(); index++) {
            releaseOutbound(outboundReleaseBatch.get(index));
        }
        outboundReleaseBatch.clear();
    }

    private void pollReadyOutboundLocked(long now, int limit, List<QueuedOutboundPacket> ready) {
        blockedOutboundDomains.clear();
        boolean unresolvedEarlier = false;
        java.util.Iterator<QueuedOutboundPacket> iterator = outboundQueue.iterator();
        while (iterator.hasNext() && ready.size() < limit) {
            QueuedOutboundPacket next = iterator.next();
            if (next.orderedBarrier && unresolvedEarlier) break;
            String domain = next.lane.getDependencyDomain();
            if (blockedOutboundDomains.contains(domain)) {
                unresolvedEarlier = true;
                continue;
            }
            if (!next.isReady(now)) {
                blockedOutboundDomains.add(domain);
                unresolvedEarlier = true;
                if (next.orderedBarrier) break;
                continue;
            }
            iterator.remove();
            ready.add(next);
        }
        blockedOutboundDomains.clear();
    }

    private void drainReadyInbound(int limit, long budgetNanos) {
        long started = System.nanoTime();
        int released = 0;
        while (released < limit && System.nanoTime() - started < budgetNanos) {
            QueuedInboundPacket next;
            synchronized (inboundQueue) {
                next = pollReadyInboundLocked(monotonicMillis());
            }
            if (next == null) break;
            releaseInbound(next);
            released++;
        }
    }

    private QueuedInboundPacket pollReadyInboundLocked(long now) {
        blockedInboundDomains.clear();
        boolean unresolvedEarlier = false;
        java.util.Iterator<QueuedInboundPacket> iterator = inboundQueue.iterator();
        while (iterator.hasNext()) {
            QueuedInboundPacket next = iterator.next();
            if (next.orderedBarrier && unresolvedEarlier) break;
            String domain = next.lane.getDependencyDomain();
            if (blockedInboundDomains.contains(domain)) {
                unresolvedEarlier = true;
                continue;
            }
            if (!next.isReady(now)) {
                blockedInboundDomains.add(domain);
                unresolvedEarlier = true;
                if (next.orderedBarrier) break;
                continue;
            }
            iterator.remove();
            blockedInboundDomains.clear();
            return next;
        }
        blockedInboundDomains.clear();
        return null;
    }

    private void releaseOutbound(QueuedOutboundPacket queuedPacket) {
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        if (netHandler == null || netHandler.getNetworkManager() == null
                || netHandler.getNetworkManager() != queuedPacket.connection) {
            queuedPacket.fail(new ClosedChannelException());
            return;
        }

        outboundFastTrack.put(queuedPacket.packet, monotonicMillis() + FAST_TRACK_TTL_MS);
        try {
            netHandler.getNetworkManager().dispatchPacket(
                queuedPacket.packet,
                queuedPacket.combinedListeners()
            );
        } catch (Exception failure) {
            outboundFastTrack.remove(queuedPacket.packet);
            queuedPacket.fail(failure);
            logReleaseFailure("outbound", failure);
        }
    }

    private void releaseInbound(QueuedInboundPacket queuedPacket) {
        if (currentNetworkManager() != queuedPacket.connection) return;
        moduleManager.onInboundPacketReleased(queuedPacket.packet);
        if (queuedPacket.cancelOnRelease) return;
        try {
            queuedPacket.action.run();
        } catch (Exception failure) {
            logReleaseFailure("inbound", failure);
        } finally {
            moduleManager.onInboundPacketProcessed(queuedPacket.packet);
        }
    }

    public boolean isInboundQueued(Packet<?> packet) {
        if (packet == null) return false;
        synchronized (inboundQueue) {
            for (QueuedInboundPacket queued : inboundQueue) {
                if (queued.packet == packet) return true;
            }
        }
        return false;
    }

    private boolean consumeOutboundFastTrack(Packet<?> packet) {
        return outboundFastTrack.remove(packet) != null;
    }

    private void cleanupFastTrack() {
        long now = monotonicMillis();
        synchronized (outboundFastTrack) {
            java.util.Iterator<Map.Entry<Packet<?>, Long>> iterator = outboundFastTrack.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().longValue() <= now) iterator.remove();
            }
        }
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

    private void clearQueues(Throwable failure) {
        synchronized (outboundQueue) {
            while (!outboundQueue.isEmpty()) outboundQueue.poll().fail(failure);
            outboundRecovery = false;
        }
        synchronized (inboundQueue) {
            inboundQueue.clear();
            inboundRecovery = false;
        }
        outboundFastTrack.clear();
        final Channel channel = pausedInboundChannel;
        pausedInboundChannel = null;
        if (channel != null) setAutoReadAsync(channel, true);
    }

    private void discardStaleConnections(NetworkManager currentManager) {
        ClosedChannelException failure = new ClosedChannelException();
        synchronized (outboundQueue) {
            java.util.Iterator<QueuedOutboundPacket> iterator = outboundQueue.iterator();
            while (iterator.hasNext()) {
                QueuedOutboundPacket queued = iterator.next();
                if (queued.connection == currentManager) continue;
                iterator.remove();
                queued.fail(failure);
            }
        }
        synchronized (inboundQueue) {
            java.util.Iterator<QueuedInboundPacket> iterator = inboundQueue.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().connection != currentManager) iterator.remove();
            }
        }
    }

    private void setAutoReadAsync(final Channel channel, final boolean enabled) {
        try {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    if (channel.isOpen()) channel.config().setAutoRead(enabled);
                }
            });
        } catch (RuntimeException failure) {
            logReleaseFailure("inbound channel", failure);
        }
    }

    /** Overflow-only compaction; normal and requested releases remain byte-for-byte ordered. */
    private void compactMovementRuns(Module owner, OwnerToken ownerToken) {
        if (outboundQueue.size() < 2) return;

        ArrayDeque<QueuedOutboundPacket> rebuilt = new ArrayDeque<QueuedOutboundPacket>(outboundQueue.size());
        List<QueuedOutboundPacket> run = new ArrayList<QueuedOutboundPacket>();
        while (!outboundQueue.isEmpty()) {
            QueuedOutboundPacket packet = outboundQueue.poll();
            if (packet.owner == owner && ownerToken.equals(packet.ownerToken)
                    && packet.packet instanceof C03PacketPlayer) {
                run.add(packet);
                continue;
            }
            appendMovementRun(rebuilt, run);
            rebuilt.add(packet);
        }
        appendMovementRun(rebuilt, run);
        outboundQueue.addAll(rebuilt);
    }

    private void appendMovementRun(
        Queue<QueuedOutboundPacket> destination,
        List<QueuedOutboundPacket> run
    ) {
        if (run.isEmpty()) return;
        if (run.size() == 1) {
            destination.add(run.get(0));
            run.clear();
            return;
        }

        QueuedOutboundPacket last = run.get(run.size() - 1);
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        float yaw = 0.0F;
        float pitch = 0.0F;
        boolean hasPosition = false;
        boolean hasRotation = false;
        boolean onGround = false;
        long releaseAt = 0L;
        boolean indefinite = false;
        boolean orderedBarrier = false;
        List<OutboundCompletion> completions = new ArrayList<OutboundCompletion>(run.size());

        for (QueuedOutboundPacket queued : run) {
            C03PacketPlayer movement = (C03PacketPlayer) queued.packet;
            if (movement.moving) {
                x = movement.x;
                y = movement.y;
                z = movement.z;
                hasPosition = true;
            }
            if (movement.rotating) {
                yaw = movement.yaw;
                pitch = movement.pitch;
                hasRotation = true;
            }
            onGround = movement.onGround;
            releaseAt = Math.max(releaseAt, queued.releaseAt);
            indefinite |= queued.indefinite;
            orderedBarrier |= queued.orderedBarrier;
            completions.addAll(queued.completions);
        }

        C03PacketPlayer merged;
        if (hasPosition && hasRotation) {
            merged = new C03PacketPlayer.C06PacketPlayerPosLook(x, y, z, yaw, pitch, onGround);
        } else if (hasPosition) {
            merged = new C03PacketPlayer.C04PacketPlayerPosition(x, y, z, onGround);
        } else if (hasRotation) {
            merged = new C03PacketPlayer.C05PacketPlayerLook(yaw, pitch, onGround);
        } else {
            merged = new C03PacketPlayer(onGround);
        }
        destination.add(new QueuedOutboundPacket(merged, last.owner, last.ownerToken,
            last.connection, releaseAt, indefinite, completions, last.lane, orderedBarrier));
        run.clear();
    }

    private static long deadline(long now, PacketDecision decision) {
        if (decision.getReleasePolicy() == PacketReleasePolicy.EXPLICIT_FLUSH) return Long.MAX_VALUE;
        return now + Math.max(0, decision.getDelayMillis());
    }

    private static <T extends OwnedQueuedPacket> void markOwnerReady(
            Queue<T> queue, OwnerToken ownerToken) {
        if (ownerToken == null) return;
        synchronized (queue) {
            for (T packet : queue) {
                if (ownerToken.equals(packet.ownerToken)) packet.makeReady();
            }
        }
    }

    private static <T extends OwnedQueuedPacket> void markAllReadyLocked(Queue<T> queue) {
        for (T packet : queue) packet.makeReady();
    }

    private static <T extends OwnedQueuedPacket> int countOwner(
            Queue<T> queue, OwnerToken ownerToken) {
        if (ownerToken == null) return 0;
        int count = 0;
        for (T packet : queue) {
            if (ownerToken.equals(packet.ownerToken)) count++;
        }
        return count;
    }

    private static <T extends OwnedQueuedPacket> Module findOwner(Queue<T> queue, OwnerToken ownerToken) {
        if (ownerToken == null) return null;
        for (T packet : queue) {
            if (ownerToken.equals(packet.ownerToken)) return packet.owner;
        }
        return null;
    }

    private static <T extends OwnedQueuedPacket> String ownerSummary(Queue<T> queue) {
        synchronized (queue) {
            if (queue.isEmpty()) return "None";
            Set<String> owners = new LinkedHashSet<String>();
            for (T packet : queue) owners.add(packet.owner == null ? "Transport" : packet.owner.getName());
            if (owners.size() <= 3) return join(owners);
            return owners.iterator().next() + " +" + (owners.size() - 1);
        }
    }

    private boolean canBypassOutboundOrdering(Queue<QueuedOutboundPacket> queue, Packet<?> packet) {
        boolean ownedBacklog = false;
        for (QueuedOutboundPacket queued : queue) {
            if (queued.owner == null) continue;
            ownedBacklog = true;
            if (!moduleManager.shouldBypassOutboundOrdering(queued.owner, packet)) return false;
        }
        return ownedBacklog;
    }

    private boolean canBypassInboundOrdering(Queue<QueuedInboundPacket> queue, PacketLane lane) {
        String domain = lane == null ? "transport" : lane.getDependencyDomain();
        for (QueuedInboundPacket queued : queue) {
            if (domain.equals(queued.lane.getDependencyDomain())) return false;
        }
        return true;
    }

    private static String join(Set<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private int outboundQueueSize() {
        synchronized (outboundQueue) {
            return outboundQueue.size();
        }
    }

    private int inboundQueueSize() {
        synchronized (inboundQueue) {
            return inboundQueue.size();
        }
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private NetworkManager currentNetworkManager() {
        NetHandlerPlayClient handler = minecraft.getNetHandler();
        return handler == null ? null : handler.getNetworkManager();
    }

    private void bindConnection(NetworkManager manager) {
        synchronized (connectionLock) {
            if (closed) return;
            if (activeNetworkManager == manager) return;
            clearQueues(new ClosedChannelException());
            activeNetworkManager = manager;
            lastFlushReason = "Connection changed";
        }
    }

    private void logReleaseFailure(String direction, Exception failure) {
        long now = monotonicMillis();
        if (now - lastReleaseFailureAt < 5_000L) return;
        lastReleaseFailureAt = now;
        AgentLog.error("Unable to release delayed " + direction + " packet", failure);
    }

    private void logOverflow(String direction, String owner) {
        long now = monotonicMillis();
        if (now - lastOverflowLogAt < 5_000L) return;
        lastOverflowLogAt = now;
        AgentLog.info(String.format(Locale.ROOT, "Ordered %s overflow recovery; owner=%s", direction, owner));
    }

    private abstract static class OwnedQueuedPacket {
        protected final Module owner;
        protected final OwnerToken ownerToken;
        protected final NetworkManager connection;
        protected final PacketLane lane;
        protected final boolean orderedBarrier;
        protected long releaseAt;
        protected boolean indefinite;

        private OwnedQueuedPacket(Module owner, OwnerToken ownerToken, NetworkManager connection,
                long releaseAt, boolean indefinite, PacketLane lane, boolean orderedBarrier) {
            this.owner = owner;
            this.ownerToken = ownerToken;
            this.connection = connection;
            this.releaseAt = releaseAt;
            this.indefinite = indefinite;
            this.orderedBarrier = orderedBarrier;
            this.lane = lane == null
                ? new PacketLane(null, PacketLane.Direction.OUTBOUND, "transport") : lane;
        }

        protected final boolean isReady(long now) {
            return !indefinite && releaseAt <= now;
        }

        protected final void makeReady() {
            indefinite = false;
            releaseAt = 0L;
        }
    }

    private static final class QueuedOutboundPacket extends OwnedQueuedPacket {
        private final Packet<?> packet;
        private final List<OutboundCompletion> completions;

        private QueuedOutboundPacket(
            Packet<?> packet,
            Module owner,
            OwnerToken ownerToken,
            NetworkManager connection,
            long releaseAt,
            boolean indefinite,
            OutboundCompletion completion,
            PacketLane lane,
            boolean orderedBarrier
        ) {
            this(packet, owner, ownerToken, connection, releaseAt, indefinite,
                Collections.singletonList(completion), lane, orderedBarrier);
        }

        private QueuedOutboundPacket(
            Packet<?> packet,
            Module owner,
            OwnerToken ownerToken,
            NetworkManager connection,
            long releaseAt,
            boolean indefinite,
            List<OutboundCompletion> completions,
            PacketLane lane,
            boolean orderedBarrier
        ) {
            super(owner, ownerToken, connection, releaseAt, indefinite, lane, orderedBarrier);
            this.packet = packet;
            this.completions = completions;
        }

        @SuppressWarnings("unchecked")
        private GenericFutureListener<? extends Future<? super Void>>[] combinedListeners() {
            int count = 0;
            for (OutboundCompletion completion : completions) count += completion.listenerCount();
            if (count == 0) return null;

            GenericFutureListener<? extends Future<? super Void>>[] combined =
                (GenericFutureListener<? extends Future<? super Void>>[]) new GenericFutureListener[count];
            int index = 0;
            for (OutboundCompletion completion : completions) {
                index = completion.appendTo(combined, index);
            }
            return combined;
        }

        private void fail(Throwable failure) {
            for (OutboundCompletion completion : completions) completion.fail(failure, connection);
        }
    }

    private static final class OutboundCompletion {
        private final GenericFutureListener<? extends Future<? super Void>>[] listeners;
        private final ChannelPromise promise;

        private OutboundCompletion(
            GenericFutureListener<? extends Future<? super Void>>[] listeners,
            ChannelPromise promise
        ) {
            this.listeners = listeners;
            this.promise = promise;
        }

        private int listenerCount() {
            return (listeners == null ? 0 : listeners.length) + (promise == null ? 0 : 1);
        }

        private int appendTo(
            GenericFutureListener<? extends Future<? super Void>>[] destination,
            int index
        ) {
            if (listeners != null) {
                for (GenericFutureListener<? extends Future<? super Void>> listener : listeners) {
                    destination[index++] = listener;
                }
            }
            if (promise != null) destination[index++] = promiseBridge(promise);
            return index;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void fail(Throwable failure, NetworkManager connection) {
            Future failedFuture = null;
            if (promise != null) {
                promise.tryFailure(failure);
                failedFuture = promise;
            } else if (connection != null && connection.channel != null) {
                failedFuture = connection.channel.newFailedFuture(failure);
            }
            if (listeners == null || failedFuture == null) return;
            for (GenericFutureListener listener : listeners) {
                if (listener == null) continue;
                try {
                    listener.operationComplete(failedFuture);
                } catch (Exception ignored) {
                    // Listener failures must not prevent the remaining completions.
                }
            }
        }

        private static GenericFutureListener<Future<? super Void>> promiseBridge(final ChannelPromise promise) {
            return new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) {
                    if (future.isSuccess()) {
                        promise.trySuccess();
                    } else {
                        Throwable cause = future.cause();
                        promise.tryFailure(cause == null ? new ClosedChannelException() : cause);
                    }
                }
            };
        }
    }

    private static final class QueuedInboundPacket extends OwnedQueuedPacket {
        private final Packet<?> packet;
        private final Runnable action;
        private final boolean cancelOnRelease;

        private QueuedInboundPacket(
            Packet<?> packet,
            Module owner,
            OwnerToken ownerToken,
            NetworkManager connection,
            long releaseAt,
            boolean indefinite,
            Runnable action,
            boolean cancelOnRelease,
            PacketLane lane,
            boolean orderedBarrier
        ) {
            super(owner, ownerToken, connection, releaseAt, indefinite, lane, orderedBarrier);
            this.packet = packet;
            this.action = action;
            this.cancelOnRelease = cancelOnRelease;
        }
    }
}
