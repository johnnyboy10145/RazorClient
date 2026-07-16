package com.razorclient.network;

import com.razorclient.runtime.OwnerToken;

/** Immutable logical lane used to isolate packet owners without losing dependency ordering. */
public final class PacketLane {
    public enum Direction { INBOUND, OUTBOUND }

    private final OwnerToken owner;
    private final Direction direction;
    private final String dependencyDomain;

    public PacketLane(OwnerToken owner, Direction direction, String dependencyDomain) {
        this.owner = owner;
        this.direction = direction;
        this.dependencyDomain = dependencyDomain == null ? "transport" : dependencyDomain;
    }

    public OwnerToken getOwner() { return owner; }
    public Direction getDirection() { return direction; }
    public String getDependencyDomain() { return dependencyDomain; }

    @Override public int hashCode() {
        int value = 31 * direction.hashCode() + dependencyDomain.hashCode();
        return 31 * value + (owner == null ? 0 : owner.hashCode());
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof PacketLane)) return false;
        PacketLane lane = (PacketLane) other;
        return direction == lane.direction && dependencyDomain.equals(lane.dependencyDomain)
            && (owner == null ? lane.owner == null : owner.equals(lane.owner));
    }
}
