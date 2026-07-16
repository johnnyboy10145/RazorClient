package com.razorclient.feature.module.impl.clutch;

/** Owns one recovery session and bounds states that would otherwise retain leases forever. */
public final class ClutchSession {
    public enum BlockedReason {
        NONE,
        NO_BLOCK,
        NO_FACE,
        SUPPRESSED,
        PLACEMENT_FAILURE
    }

    private static final long NO_BLOCK_TIMEOUT_NANOS = 750_000_000L;
    private static final long NO_FACE_TIMEOUT_NANOS = 1_000_000_000L;
    private static final long SUPPRESSED_TIMEOUT_NANOS = 1_250_000_000L;
    private static final long FAILURE_TIMEOUT_NANOS = 1_500_000_000L;

    private ClutchPhase phase = ClutchPhase.IDLE;
    private String detail = "Ready";
    private Object worldIdentity;
    private int playerEntityId = -1;
    private long phaseStartedNanos;
    private long blockedSinceNanos;
    private BlockedReason blockedReason = BlockedReason.NONE;
    private int blocksPlaced;
    private int placementFailures;
    private boolean emergencyConfirmed;

    public void begin(Object worldIdentity, int playerEntityId, long nowNanos) {
        reset();
        this.worldIdentity = worldIdentity;
        this.playerEntityId = playerEntityId;
        transition(ClutchPhase.ARMED, "Armed", nowNanos);
    }

    public boolean matches(Object worldIdentity, int playerEntityId) {
        return phase != ClutchPhase.IDLE
            && this.worldIdentity == worldIdentity
            && this.playerEntityId == playerEntityId;
    }

    public void transition(ClutchPhase phase, String detail, long nowNanos) {
        if (this.phase != phase) phaseStartedNanos = nowNanos;
        this.phase = phase;
        this.detail = detail == null ? "" : detail;
    }

    public boolean markBlocked(BlockedReason reason, String detail, long nowNanos) {
        if (blockedReason != reason) {
            blockedReason = reason;
            blockedSinceNanos = nowNanos;
        }
        this.detail = detail;
        return nowNanos - blockedSinceNanos >= timeoutFor(reason);
    }

    public void clearBlocked() {
        blockedReason = BlockedReason.NONE;
        blockedSinceNanos = 0L;
    }

    public void clearBlocked(BlockedReason reason) {
        if (blockedReason == reason) clearBlocked();
    }

    public void placementConfirmed() {
        blocksPlaced++;
        placementFailures = 0;
        emergencyConfirmed = true;
        clearBlocked();
    }

    public boolean placementFailed(long nowNanos) {
        placementFailures++;
        return markBlocked(BlockedReason.PLACEMENT_FAILURE,
            placementFailures >= 3 ? "Replanning" : "Retry", nowNanos);
    }

    public void clearFailures() {
        placementFailures = 0;
        if (blockedReason == BlockedReason.PLACEMENT_FAILURE) clearBlocked();
    }

    public void reset() {
        phase = ClutchPhase.IDLE;
        detail = "Ready";
        worldIdentity = null;
        playerEntityId = -1;
        phaseStartedNanos = 0L;
        blockedSinceNanos = 0L;
        blockedReason = BlockedReason.NONE;
        blocksPlaced = 0;
        placementFailures = 0;
        emergencyConfirmed = false;
    }

    private static long timeoutFor(BlockedReason reason) {
        switch (reason) {
            case NO_BLOCK: return NO_BLOCK_TIMEOUT_NANOS;
            case NO_FACE: return NO_FACE_TIMEOUT_NANOS;
            case SUPPRESSED: return SUPPRESSED_TIMEOUT_NANOS;
            case PLACEMENT_FAILURE: return FAILURE_TIMEOUT_NANOS;
            default: return Long.MAX_VALUE;
        }
    }

    public ClutchPhase getPhase() { return phase; }
    public String getDetail() { return detail; }
    public Object getWorldIdentity() { return worldIdentity; }
    public int getPlayerEntityId() { return playerEntityId; }
    public long getPhaseStartedNanos() { return phaseStartedNanos; }
    public int getBlocksPlaced() { return blocksPlaced; }
    public int getPlacementFailures() { return placementFailures; }
    public boolean isEmergencyConfirmed() { return emergencyConfirmed; }
    public boolean isActive() { return phase != ClutchPhase.IDLE; }
}
