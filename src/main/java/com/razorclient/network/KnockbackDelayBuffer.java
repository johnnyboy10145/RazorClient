package com.razorclient.network;

/**
 * Compatibility facade retained for the legacy Forge entrypoint.
 * Knockback delay now uses PacketDelayManager's owner-scoped inbound lane.
 */
public final class KnockbackDelayBuffer {
    public int getIncomingQueueSize() {
        return 0;
    }

    public void onClientTick() {
    }

    public void flushAllIncoming() {
    }

    public boolean shouldBufferIncoming() {
        return false;
    }

    public void bufferIncoming(Runnable action) {
        if (action != null) action.run();
    }
}
