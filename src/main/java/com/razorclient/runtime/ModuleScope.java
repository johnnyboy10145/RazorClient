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
    private boolean active;
    private int targetEntityId = -1;
    private String status = "Idle";

    public ModuleScope(String owner) {
        this.owner = owner;
    }

    public synchronized void attach(ResourceArbiter arbiter) {
        if (this.arbiter != null && this.arbiter != arbiter) {
            throw new IllegalStateException("Module scope already attached: " + owner);
        }
        this.arbiter = arbiter;
    }

    public synchronized void activate() {
        active = true;
    }

    public synchronized boolean isActive() { return active; }
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
        synchronized (this) {
            if (!active || arbiter == null) return null;
            value = arbiter;
        }
        ResourceArbiter.Lease lease = value.acquire(resource, owner, priority, durationTicks, restoreAction);
        synchronized (this) {
            if (active && arbiter == value) return lease;
        }
        if (lease != null) lease.close();
        return null;
    }

    public void release(ResourceArbiter.Resource resource) {
        ResourceArbiter value;
        synchronized (this) { value = arbiter; }
        if (value != null) value.releaseOwner(owner, resource);
    }

    public void cleanupInput(ModuleResetReason reason) {
        List<Runnable> actions;
        ResourceArbiter value;
        synchronized (this) {
            actions = new ArrayList<Runnable>(inputCleanupActions);
            value = arbiter;
        }
        if (value != null) value.releaseOwner(owner);
        runActions(actions, "input cleanup", reason);
    }

    public void reset(ModuleResetReason reason) {
        List<Runnable> actions;
        ResourceArbiter value;
        synchronized (this) {
            active = false;
            state.clear();
            deadlines.clear();
            targetEntityId = -1;
            status = "Idle";
            actions = new ArrayList<Runnable>(resetActions);
            value = arbiter;
        }
        if (value != null) value.releaseOwner(owner);
        runActions(actions, "scope reset", reason);
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
}
