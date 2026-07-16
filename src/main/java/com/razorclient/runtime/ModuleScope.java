package com.razorclient.runtime;

import com.razorclient.feature.module.ModuleResetReason;
import com.razorclient.inject.AgentLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Private mutable state and cleanup ownership for one module. */
public final class ModuleScope {
    private final String owner;
    private final Random random = new Random();
    private final Map<String, Object> state = new HashMap<String, Object>();
    private final Map<String, Long> deadlines = new HashMap<String, Long>();
    private final List<Runnable> resetActions = new ArrayList<Runnable>();
    private final List<Runnable> inputCleanupActions = new ArrayList<Runnable>();
    private ResourceArbiter arbiter;
    private ClientThreadScheduler scheduler;
    private OwnerToken ownerToken;
    private boolean active;
    private int targetEntityId = -1;
    private String status = "Idle";

    public ModuleScope(String owner) {
        this.owner = owner;
    }

    public synchronized void attach(ResourceArbiter arbiter, ClientThreadScheduler scheduler) {
        if (this.arbiter != null && this.arbiter != arbiter) {
            throw new IllegalStateException("Module scope already attached: " + owner);
        }
        this.arbiter = arbiter;
        this.scheduler = scheduler;
    }

    public synchronized void activate(long activationGeneration) {
        if (activationGeneration <= 0L) throw new IllegalArgumentException("activationGeneration");
        active = true;
        ownerToken = OwnerToken.issue(owner, activationGeneration);
    }

    /** Starts a rollback-safe activation transaction. */
    public synchronized Activation beginActivation(long activationGeneration) {
        if (active) throw new IllegalStateException("Module scope already active: " + owner);
        activate(activationGeneration);
        return new Activation(this, ownerToken);
    }

    public synchronized boolean isActive() { return active; }
    public synchronized OwnerToken getOwnerToken() { return ownerToken; }
    public String getOwner() { return owner; }
    public Random getRandom() { return random; }

    public synchronized void putState(String key, Object value) {
        if (value == null) state.remove(key); else state.put(key, value);
    }

    public synchronized Object getState(String key) { return state.get(key); }
    public synchronized void removeState(String key) { state.remove(key); }

    public synchronized void setDeadlineNanos(String key, long deadlineNanos) {
        deadlines.put(key, Long.valueOf(deadlineNanos));
    }

    public synchronized boolean isDeadlineReached(String key, long nowNanos) {
        Long deadline = deadlines.get(key);
        return deadline != null && nowNanos >= deadline.longValue();
    }

    public synchronized void clearDeadline(String key) { deadlines.remove(key); }
    public synchronized int getTargetEntityId() { return targetEntityId; }
    public synchronized void setTargetEntityId(int targetEntityId) { this.targetEntityId = targetEntityId; }
    public synchronized String getStatus() { return status; }
    public synchronized void setStatus(String status) { this.status = status == null ? "" : status; }

    public synchronized void addResetAction(Runnable action) {
        if (action != null && !resetActions.contains(action)) resetActions.add(action);
    }

    public synchronized void addInputCleanupAction(Runnable action) {
        if (action != null && !inputCleanupActions.contains(action)) inputCleanupActions.add(action);
    }

    public ResourceArbiter.Lease acquire(ResourceArbiter.Resource resource, int priority,
            int durationTicks, Runnable restoreAction) {
        ResourceArbiter value;
        OwnerToken token;
        synchronized (this) {
            if (!active || arbiter == null) return null;
            value = arbiter;
            token = ownerToken;
        }
        ResourceArbiter.Lease lease = value.acquire(resource, token, priority, durationTicks, restoreAction);
        synchronized (this) {
            if (active && arbiter == value) return lease;
        }
        if (lease != null) lease.close();
        return null;
    }

    public void release(ResourceArbiter.Resource resource) {
        ResourceArbiter value;
        OwnerToken token;
        synchronized (this) { value = arbiter; token = ownerToken; }
        if (value != null) value.releaseOwner(token, resource);
    }

    public void cleanupInput(ModuleResetReason reason) {
        List<Runnable> actions;
        ResourceArbiter value;
        OwnerToken token;
        synchronized (this) {
            actions = new ArrayList<Runnable>(inputCleanupActions);
            value = arbiter;
            token = ownerToken;
        }
        if (value != null) value.releaseOwner(token);
        runActions(actions, "input cleanup", reason);
    }

    public void reset(ModuleResetReason reason) {
        List<Runnable> actions;
        ResourceArbiter value;
        ClientThreadScheduler taskScheduler;
        OwnerToken token;
        synchronized (this) {
            active = false;
            token = ownerToken;
            ownerToken = null;
            state.clear();
            deadlines.clear();
            targetEntityId = -1;
            status = "Idle";
            actions = new ArrayList<Runnable>(resetActions);
            value = arbiter;
            taskScheduler = scheduler;
        }
        if (taskScheduler != null) taskScheduler.discardOwner(token);
        if (value != null) value.releaseOwner(token);
        runActions(actions, "scope reset", reason);
    }

    private synchronized void commitActivation(OwnerToken token) {
        if (!active || ownerToken != token) throw new IllegalStateException("Stale activation: " + owner);
    }

    private void rollbackActivation(OwnerToken token) {
        synchronized (this) {
            if (!active || ownerToken != token) return;
        }
        reset(ModuleResetReason.DISABLED);
    }

    private void runActions(List<Runnable> actions, String operation, ModuleResetReason reason) {
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (Throwable failure) {
                AgentLog.error(owner + " " + operation + " failed (" + reason + ")", failure);
            }
        }
    }

    public static final class Activation implements AutoCloseable {
        private final ModuleScope scope;
        private final OwnerToken token;
        private boolean committed;
        private boolean closed;

        private Activation(ModuleScope scope, OwnerToken token) {
            this.scope = scope;
            this.token = token;
        }

        public OwnerToken getOwnerToken() { return token; }

        public void commit() {
            if (closed) throw new IllegalStateException("Activation already closed");
            scope.commitActivation(token);
            committed = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!committed) scope.rollbackActivation(token);
        }
    }
}
