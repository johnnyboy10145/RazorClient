package com.razorclient.runtime;

import com.razorclient.feature.module.Module;
import com.razorclient.inject.AgentLog;
import java.util.HashMap;
import java.util.Map;

/** Rate-limits callback failures while allowing the remaining modules to continue. */
public final class ModuleFaultBarrier {
    private static final long LOG_INTERVAL_NANOS = 30_000_000_000L;
    private static final int MAX_FAILURE_KEYS = 128;
    private final Map<String, FailureState> failures = new HashMap<String, FailureState>();

    public synchronized void report(Module module, String callback, Throwable failure) {
        String moduleName = module == null ? "runtime" : module.getName();
        String key = moduleName + ':' + callback;
        long now = System.nanoTime();
        FailureState state = failures.get(key);
        if (state == null) {
            if (failures.size() >= MAX_FAILURE_KEYS) failures.clear();
            state = new FailureState();
            failures.put(key, state);
        }
        state.count++;
        if (state.lastLogNanos == 0L || now - state.lastLogNanos >= LOG_INTERVAL_NANOS) {
            state.lastLogNanos = now;
            AgentLog.error("Module callback failed: " + key + " count=" + state.count, failure);
        }
    }

    public synchronized void clear() { failures.clear(); }

    private static final class FailureState {
        private long lastLogNanos;
        private int count;
    }
}
