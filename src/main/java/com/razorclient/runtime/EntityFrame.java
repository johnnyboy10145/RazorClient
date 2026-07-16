package com.razorclient.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Arrays;

/** Generation-owned immutable entity collection published once per real client tick. */
public final class EntityFrame {
    public static final EntityFrame EMPTY = new EntityFrame(0L, 0L, new EntityRecord[0]);

    private final long tickSequence;
    private final long sessionGeneration;
    private final EntityRecord[] records;
    private final List<EntityRecord> view;
    private final int[] lookup;
    private final int lookupMask;

    EntityFrame(long tickSequence, long sessionGeneration, EntityRecord[] source) {
        this.tickSequence = tickSequence;
        this.sessionGeneration = sessionGeneration;
        this.records = source == null ? new EntityRecord[0] : source;
        this.view = Collections.unmodifiableList(Arrays.asList(this.records));
        int capacity = 1;
        while (capacity < Math.max(2, records.length << 1)) capacity <<= 1;
        this.lookup = new int[capacity];
        Arrays.fill(this.lookup, -1);
        this.lookupMask = capacity - 1;
        for (int index = 0; index < records.length; index++) {
            EntityRecord record = records[index];
            if (record == null) continue;
            int slot = mix(record.getEntityId()) & lookupMask;
            while (this.lookup[slot] != -1) slot = (slot + 1) & lookupMask;
            this.lookup[slot] = index;
        }
    }

    public long getTickSequence() { return tickSequence; }
    public long getSessionGeneration() { return sessionGeneration; }
    public int size() { return records.length; }
    public EntityRecord get(int index) { return records[index]; }
    public List<EntityRecord> asList() { return view; }
    public EntityRecord find(int entityId) {
        int slot = mix(entityId) & lookupMask;
        while (lookup[slot] != -1) {
            EntityRecord record = records[lookup[slot]];
            if (record != null && record.getEntityId() == entityId) return record;
            slot = (slot + 1) & lookupMask;
        }
        return null;
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }
}
