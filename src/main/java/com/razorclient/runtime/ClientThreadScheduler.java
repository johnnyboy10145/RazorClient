package com.razorclient.runtime;

import com.razorclient.inject.AgentLog;
import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded single-consumer scheduler for Minecraft state mutations. */
public final class ClientThreadScheduler {
    private static final int MAX_PENDING = 1024;
    private static final int MAX_PER_TICK = 256;
    private static final long MAX_DRAIN_NANOS = 2_000_000L;

    private final Deque<ScheduledTask> pending = new ArrayDeque<ScheduledTask>();
    private Thread clientThread;
    private long generation;
    private boolean closed;

    public synchronized boolean submit(OwnerToken owner, Runnable action) {
        if (action == null || closed || pending.size() >= MAX_PENDING) return false;
        pending.addLast(new ScheduledTask(owner, action, generation));
        return true;
    }

    public synchronized boolean isClientThread() {
        return clientThread != null && Thread.currentThread() == clientThread;
    }

    public void bindAndDrain() {
        synchronized (this) {
            if (closed) return;
            if (clientThread == null) clientThread = Thread.currentThread();
            if (clientThread != Thread.currentThread()) {
                throw new IllegalStateException("Client scheduler drained by multiple threads");
            }
        }

        long started = System.nanoTime();
        for (int count = 0; count < MAX_PER_TICK && System.nanoTime() - started < MAX_DRAIN_NANOS; count++) {
            ScheduledTask task;
            synchronized (this) {
                task = pending.pollFirst();
                if (task == null) return;
                if (task.sessionGeneration != generation) continue;
            }
            try {
                task.action.run();
            } catch (Throwable failure) {
                AgentLog.error("Client task failed owner=" + (task.owner == null ? "runtime" : task.owner), failure);
            }
        }
    }

    public synchronized void discardOwner(OwnerToken owner) {
        if (owner == null || pending.isEmpty()) return;
        pending.removeIf(task -> owner.equals(task.owner));
    }

    public synchronized void advanceSession() {
        generation++;
        pending.clear();
    }

    public synchronized int getPendingCount() { return pending.size(); }

    public synchronized void close() {
        closed = true;
        generation++;
        pending.clear();
        clientThread = null;
    }

    private static final class ScheduledTask {
        private final OwnerToken owner;
        private final Runnable action;
        private final long sessionGeneration;

        private ScheduledTask(OwnerToken owner, Runnable action, long sessionGeneration) {
            this.owner = owner;
            this.action = action;
            this.sessionGeneration = sessionGeneration;
        }
    }
}
