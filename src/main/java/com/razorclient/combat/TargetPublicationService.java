package com.razorclient.combat;

import com.razorclient.runtime.OwnerToken;
import com.razorclient.runtime.TickContext;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Owner-isolated, short-lived combat target publication for visual consumers. */
public final class TargetPublicationService {
    private final Map<OwnerToken, Publication> publications = new HashMap<OwnerToken, Publication>();

    public synchronized void publish(OwnerToken owner, int entityId, int priority, TickContext tick) {
        if (owner == null || entityId < 0 || tick == null || !tick.isSessionAvailable()) return;
        publications.put(owner, new Publication(entityId, priority, tick.getSequence(), tick.getSessionGeneration()));
        prune(tick);
    }

    public synchronized int activeTarget(TickContext tick) {
        if (tick == null) return -1;
        prune(tick);
        Publication selected = null;
        for (Publication publication : publications.values()) {
            if (selected == null || publication.priority > selected.priority) selected = publication;
        }
        return selected == null ? -1 : selected.entityId;
    }

    public synchronized void clear(OwnerToken owner) {
        if (owner != null) publications.remove(owner);
    }

    public synchronized void clearAll() { publications.clear(); }

    private void prune(TickContext tick) {
        Iterator<Publication> iterator = publications.values().iterator();
        while (iterator.hasNext()) {
            Publication value = iterator.next();
            if (value.sessionGeneration != tick.getSessionGeneration() || value.tickSequence < tick.getSequence() - 1L) {
                iterator.remove();
            }
        }
    }

    private static final class Publication {
        private final int entityId;
        private final int priority;
        private final long tickSequence;
        private final long sessionGeneration;

        private Publication(int entityId, int priority, long tickSequence, long sessionGeneration) {
            this.entityId = entityId;
            this.priority = priority;
            this.tickSequence = tickSequence;
            this.sessionGeneration = sessionGeneration;
        }
    }
}
