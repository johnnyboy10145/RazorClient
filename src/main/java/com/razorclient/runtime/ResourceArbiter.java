package com.razorclient.runtime;

import com.razorclient.inject.AgentLog;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Owner-scoped arbitration for Minecraft resources that only permit one writer. */
public final class ResourceArbiter {
    public enum Resource {
        SERVER_ROTATION,
        MODEL_ROTATION,
        ATTACK_ACTION,
        USE_ACTION,
        SNEAK_INPUT,
        SPRINT_INPUT,
        HOTBAR_SLOT,
        CLIENT_TIMER
    }

    private final Map<Resource, Lease> leases = new EnumMap<Resource, Lease>(Resource.class);
    private long tickSequence;
    private long generation;

    public Lease acquire(Resource resource, String owner, int priority, int durationTicks,
            Runnable restoreAction) {
        if (resource == null || owner == null || owner.isEmpty()) return null;
        Lease displaced = null;
        Lease acquired;
        synchronized (this) {
            int lifetime = Math.max(1, durationTicks);
            Lease current = leases.get(resource);
            if (current != null && current.valid) {
                if (current.owner.equals(owner)) {
                    current.priority = priority;
                    current.expiresAt = tickSequence + lifetime;
                    if (restoreAction != null) current.restoreAction = restoreAction;
                    return current;
                }
                if (priority <= current.priority) return null;
                markInvalid(current);
                displaced = current;
            }
            acquired = new Lease(this, resource, owner, priority, tickSequence + lifetime, ++generation,
                restoreAction);
            leases.put(resource, acquired);
        }
        restore(displaced);
        return acquired.isValid() ? acquired : null;
    }

    public void advanceTick(long sequence) {
        List<Lease> expired = new ArrayList<Lease>();
        synchronized (this) {
            tickSequence = sequence;
            Iterator<Lease> iterator = leases.values().iterator();
            while (iterator.hasNext()) {
                Lease lease = iterator.next();
                if (lease.expiresAt <= sequence) {
                    iterator.remove();
                    markInvalid(lease);
                    expired.add(lease);
                }
            }
        }
        restoreAll(expired);
    }

    public void releaseOwner(String owner) {
        if (owner == null) return;
        List<Lease> released = new ArrayList<Lease>();
        synchronized (this) {
            Iterator<Lease> iterator = leases.values().iterator();
            while (iterator.hasNext()) {
                Lease lease = iterator.next();
                if (owner.equals(lease.owner)) {
                    iterator.remove();
                    markInvalid(lease);
                    released.add(lease);
                }
            }
        }
        restoreAll(released);
    }

    public void releaseOwner(String owner, Resource resource) {
        Lease released = null;
        synchronized (this) {
            Lease lease = leases.get(resource);
            if (lease != null && lease.owner.equals(owner)) {
                leases.remove(resource);
                markInvalid(lease);
                released = lease;
            }
        }
        restore(released);
    }

    public void clearAll() {
        List<Lease> active;
        synchronized (this) {
            active = new ArrayList<Lease>(leases.values());
            leases.clear();
            for (Lease lease : active) markInvalid(lease);
        }
        restoreAll(active);
    }

    public void clear(Resource resource) {
        Lease released;
        synchronized (this) {
            released = leases.remove(resource);
            if (released != null) markInvalid(released);
        }
        restore(released);
    }

    public synchronized String getOwner(Resource resource) {
        Lease lease = leases.get(resource);
        return lease == null || !lease.valid ? "None" : lease.owner;
    }

    public synchronized boolean isOwner(Resource resource, String owner) {
        Lease lease = leases.get(resource);
        return lease != null && lease.valid && lease.owner.equals(owner);
    }

    private synchronized boolean renew(Lease lease, int durationTicks) {
        Lease active = leases.get(lease.resource);
        if (active != lease || !lease.valid) return false;
        lease.expiresAt = tickSequence + Math.max(1, durationTicks);
        return true;
    }

    private void release(Lease lease) {
        Lease released = null;
        synchronized (this) {
            if (leases.get(lease.resource) == lease) {
                leases.remove(lease.resource);
                markInvalid(lease);
                released = lease;
            }
        }
        restore(released);
    }

    private static void markInvalid(Lease lease) {
        if (lease == null || !lease.valid) return;
        lease.valid = false;
    }

    private void restoreAll(List<Lease> released) {
        for (Lease lease : released) restore(lease);
    }

    private void restore(Lease lease) {
        if (lease == null || lease.restoreAction == null) return;
        try {
            lease.restoreAction.run();
        } catch (Throwable failure) {
            AgentLog.error("Resource restore failed: " + lease.resource + " owner=" + lease.owner, failure);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ResourceArbiter arbiter;
        private final Resource resource;
        private final String owner;
        private final long generation;
        private int priority;
        private long expiresAt;
        private Runnable restoreAction;
        private volatile boolean valid = true;

        private Lease(ResourceArbiter arbiter, Resource resource, String owner, int priority,
                long expiresAt, long generation, Runnable restoreAction) {
            this.arbiter = arbiter;
            this.resource = resource;
            this.owner = owner;
            this.priority = priority;
            this.expiresAt = expiresAt;
            this.generation = generation;
            this.restoreAction = restoreAction;
        }

        public Resource getResource() { return resource; }
        public String getOwner() { return owner; }
        public int getPriority() { return priority; }
        public long getGeneration() { return generation; }
        public boolean isValid() { return valid && arbiter.isOwner(resource, owner); }
        public boolean renew(int durationTicks) { return arbiter.renew(this, durationTicks); }
        @Override public void close() { arbiter.release(this); }
    }
}
