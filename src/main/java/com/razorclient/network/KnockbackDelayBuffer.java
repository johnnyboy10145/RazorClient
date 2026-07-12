package com.razorclient.network;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.impl.KnockbackDelayModule;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;

public final class KnockbackDelayBuffer {
    private static final int MAX_RELEASES_PER_TICK = 50;
    private static final int MAX_QUEUE_SIZE = 4096;

    private final ConcurrentLinkedQueue<QueuedPacket> incomingQueue = new ConcurrentLinkedQueue<QueuedPacket>();
    private volatile long lastIncomingDeliveryMs;
    private volatile boolean flushingIncoming;

    public int getIncomingQueueSize() {
        return incomingQueue.size();
    }

    public void onClientTick() {
        long now = monotonicMillis();
        flushingIncoming = true;
        try {
            int processed = 0;
            while (processed < MAX_RELEASES_PER_TICK) {
                QueuedPacket peek = incomingQueue.peek();
                if (peek == null || peek.releaseAt > now) {
                    break;
                }

                QueuedPacket queued = incomingQueue.poll();
                if (queued == null) {
                    break;
                }

                try {
                    queued.action.run();
                } catch (Throwable ignored) {
                }
                processed++;
            }
        } finally {
            flushingIncoming = false;
        }

        if (incomingQueue.isEmpty() && !shouldBufferIncoming()) {
            lastIncomingDeliveryMs = 0L;
        }
    }

    public void flushAllIncoming() {
        flushingIncoming = true;
        try {
            while (true) {
                QueuedPacket queued = incomingQueue.poll();
                if (queued == null) {
                    break;
                }

                try {
                    queued.action.run();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            flushingIncoming = false;
            lastIncomingDeliveryMs = 0L;
        }
    }

    public boolean shouldBufferIncoming() {
        if (flushingIncoming) {
            return false;
        }

        if (Minecraft.getMinecraft().theWorld == null) {
            return false;
        }

        KnockbackDelayModule module = getModule();
        boolean knockback = module != null && module.isEnabled() && module.isHolding();
        if (!knockback) {
            if (!incomingQueue.isEmpty()) {
                flushAllIncoming();
            }
            return false;
        }

        return true;
    }

    public void bufferIncoming(Runnable action) {
        if (flushingIncoming) {
            try {
                action.run();
            } catch (Throwable ignored) {
            }
            return;
        }

        if (incomingQueue.size() >= MAX_QUEUE_SIZE) {
            flushAllIncoming();
        }

        long now = monotonicMillis();
        long delayMs = 0L;

        KnockbackDelayModule module = getModule();
        if (module != null && module.isEnabled() && module.isHolding()) {
            long delay = KnockbackDelayModule.holdPacketsUntil - now;
            if (delay > delayMs) {
                delayMs = delay;
            }
        }

        boolean active = delayMs > 0L;
        long deliverAt = active ? Math.max(now + delayMs, lastIncomingDeliveryMs) : Math.max(now, lastIncomingDeliveryMs);
        lastIncomingDeliveryMs = deliverAt;
        incomingQueue.offer(new QueuedPacket(deliverAt, action));
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private KnockbackDelayModule getModule() {
        RazorClient client = RazorClient.getInstance();
        if (client == null) {
            return null;
        }
        return client.getModuleManager().getModule(KnockbackDelayModule.class);
    }

    private static final class QueuedPacket {
        private final long releaseAt;
        private final Runnable action;

        private QueuedPacket(long releaseAt, Runnable action) {
            this.releaseAt = releaseAt;
            this.action = action;
        }
    }
}
