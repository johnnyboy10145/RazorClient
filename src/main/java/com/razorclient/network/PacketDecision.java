package com.razorclient.network;

import com.razorclient.feature.module.Module;
import com.razorclient.runtime.OwnerToken;

/** Atomic result of packet mutation/cancellation/hold arbitration. */
public final class PacketDecision {
    public enum Action { PASS, MUTATE, CANCEL, HOLD, FLUSH_THEN_PASS }

    private final Action action;
    private final Module owner;
    private final OwnerToken ownerToken;
    private final int priority;
    private final int delayMillis;
    private final PacketReleasePolicy releasePolicy;
    private final PacketLane lane;
    private final boolean cancelOnRelease;

    public PacketDecision(Action action, Module owner, OwnerToken ownerToken, int priority,
            int delayMillis, PacketReleasePolicy releasePolicy, PacketLane lane) {
        this(action, owner, ownerToken, priority, delayMillis, releasePolicy, lane, action == Action.CANCEL);
    }

    public PacketDecision(Action action, Module owner, OwnerToken ownerToken, int priority,
            int delayMillis, PacketReleasePolicy releasePolicy, PacketLane lane, boolean cancelOnRelease) {
        this.action = action == null ? Action.PASS : action;
        this.owner = owner;
        this.ownerToken = ownerToken;
        this.priority = priority;
        this.delayMillis = Math.max(0, delayMillis);
        this.releasePolicy = releasePolicy == null ? PacketReleasePolicy.IMMEDIATE : releasePolicy;
        this.lane = lane;
        this.cancelOnRelease = cancelOnRelease;
    }

    public Action getAction() { return action; }
    public Module getOwner() { return owner; }
    public OwnerToken getOwnerToken() { return ownerToken; }
    public int getPriority() { return priority; }
    public int getDelayMillis() { return delayMillis; }
    public PacketReleasePolicy getReleasePolicy() { return releasePolicy; }
    public PacketLane getLane() { return lane; }
    public boolean isCancelled() { return action == Action.CANCEL || cancelOnRelease; }
    public boolean shouldCancelOnRelease() { return cancelOnRelease; }
    public boolean isHeld() { return action == Action.HOLD; }
}
