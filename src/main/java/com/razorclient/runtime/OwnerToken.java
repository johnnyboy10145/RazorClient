package com.razorclient.runtime;

import java.util.concurrent.atomic.AtomicLong;

/** Identifies one activation generation of one module scope. */
public final class OwnerToken {
    private static final AtomicLong NEXT_SCOPE_ID = new AtomicLong(1L);

    private final String moduleName;
    private final long activationGeneration;
    private final long scopeId;

    private OwnerToken(String moduleName, long activationGeneration, long scopeId) {
        this.moduleName = moduleName;
        this.activationGeneration = activationGeneration;
        this.scopeId = scopeId;
    }

    static OwnerToken issue(String moduleName, long activationGeneration) {
        if (moduleName == null || moduleName.isEmpty()) {
            throw new IllegalArgumentException("moduleName");
        }
        if (activationGeneration <= 0L) {
            throw new IllegalArgumentException("activationGeneration");
        }
        return new OwnerToken(moduleName, activationGeneration, NEXT_SCOPE_ID.getAndIncrement());
    }

    public String getModuleName() { return moduleName; }
    public long getActivationGeneration() { return activationGeneration; }
    public long getScopeId() { return scopeId; }

    @Override
    public int hashCode() {
        long value = scopeId ^ (scopeId >>> 32);
        return (int) value;
    }

    @Override
    public boolean equals(Object value) {
        return value instanceof OwnerToken && ((OwnerToken) value).scopeId == scopeId;
    }

    @Override
    public String toString() {
        return moduleName + '#' + activationGeneration + ':' + scopeId;
    }
}
