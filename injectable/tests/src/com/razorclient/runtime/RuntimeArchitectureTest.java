package com.razorclient.runtime;

import com.razorclient.config.ConfigArchitectureTest;
import com.razorclient.network.PacketDecision;
import com.razorclient.network.PacketLane;
import com.razorclient.network.PacketReleasePolicy;
import com.razorclient.feature.module.ModuleResetReason;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic contract tests for the instance-owned runtime kernel. */
public final class RuntimeArchitectureTest {
    private RuntimeArchitectureTest() {}

    public static void main(String[] args) throws Exception {
        testOwnerGenerationIsolation();
        testResourcePriorityAndExpiry();
        testTransactionalModuleScope();
        testSchedulerOwnershipAndSessionCleanup();
        testClientSessionShutdown();
        testEntityFrameImmutability();
        testPacketContracts();
        ConfigArchitectureTest.run();
        System.out.println("PASS runtime architecture contracts");
    }

    private static void testTransactionalModuleScope() {
        ResourceArbiter arbiter = new ResourceArbiter();
        ClientThreadScheduler scheduler = new ClientThreadScheduler();
        ModuleScope scope = new ModuleScope("Clutch");
        scope.attach(arbiter, scheduler);
        AtomicInteger restores = new AtomicInteger();

        try (ModuleScope.Activation activation = scope.beginActivation(1L)) {
            check(scope.acquire(ResourceArbiter.Resource.USE_ACTION, 10, 1,
                restores::incrementAndGet) != null, "activation could not acquire a scoped lease");
        }
        check(!scope.isActive(), "uncommitted activation did not roll back");
        check(restores.get() == 1, "activation rollback did not restore its lease exactly once");

        try (ModuleScope.Activation activation = scope.beginActivation(2L)) {
            activation.commit();
        }
        check(scope.isActive() && scope.getOwnerToken().getActivationGeneration() == 2L,
            "committed activation did not retain its generation");
        scope.reset(ModuleResetReason.DISABLED);
        check(!scope.isActive() && scope.getOwnerToken() == null,
            "module reset retained an active owner generation");
        scheduler.close();
    }

    private static void testOwnerGenerationIsolation() {
        OwnerToken first = OwnerToken.issue("Velocity", 1L);
        OwnerToken next = OwnerToken.issue("Velocity", 2L);
        check(!first.equals(next), "activation generations must receive distinct owner tokens");
        check(first.getActivationGeneration() == 1L, "first generation was not retained");
        check(next.getActivationGeneration() == 2L, "next generation was not retained");
        check(first.getScopeId() != next.getScopeId(), "scope identifiers must be unique");
    }

    private static void testResourcePriorityAndExpiry() {
        ResourceArbiter arbiter = new ResourceArbiter();
        OwnerToken first = OwnerToken.issue("TestOwnerA", 1L);
        OwnerToken second = OwnerToken.issue("TestOwnerB", 1L);
        AtomicInteger firstRestores = new AtomicInteger();
        AtomicInteger secondRestores = new AtomicInteger();

        ResourceArbiter.Lease firstLease = arbiter.acquire(ResourceArbiter.Resource.SERVER_ROTATION,
            first, 50, 2, firstRestores::incrementAndGet);
        check(firstLease != null && firstLease.isValid(), "initial lease acquisition failed");
        check(arbiter.acquire(ResourceArbiter.Resource.SERVER_ROTATION, second, 50, 2,
            secondRestores::incrementAndGet) == null, "equal priority must not displace an owner");

        ResourceArbiter.Lease secondLease = arbiter.acquire(ResourceArbiter.Resource.SERVER_ROTATION,
            second, 60, 2, secondRestores::incrementAndGet);
        check(secondLease != null && secondLease.isValid(), "higher priority did not acquire lease");
        check(!firstLease.isValid(), "displaced lease remained valid");
        check(firstRestores.get() == 1, "displaced lease restore must execute exactly once");

        arbiter.releaseOwner(first);
        check(secondLease.isValid(), "stale owner released another owner's lease");
        arbiter.advanceTick(1L);
        check(secondLease.isValid(), "lease expired before its deadline");
        arbiter.advanceTick(2L);
        check(!secondLease.isValid(), "lease did not expire at its deadline");
        check(secondRestores.get() == 1, "expired lease restore must execute exactly once");
        secondLease.close();
        check(secondRestores.get() == 1, "closing an expired lease restored twice");
    }

    private static void testSchedulerOwnershipAndSessionCleanup() {
        ClientThreadScheduler scheduler = new ClientThreadScheduler();
        OwnerToken first = OwnerToken.issue("AimAssist", 1L);
        OwnerToken second = OwnerToken.issue("KillAura", 1L);
        AtomicInteger executed = new AtomicInteger();

        check(scheduler.submit(first, () -> executed.addAndGet(1)), "first task was rejected");
        check(scheduler.submit(second, () -> executed.addAndGet(10)), "second task was rejected");
        scheduler.discardOwner(first);
        scheduler.bindAndDrain();
        check(executed.get() == 10, "owner cleanup removed or retained the wrong task");

        check(scheduler.submit(second, () -> executed.addAndGet(100)), "session task was rejected");
        scheduler.advanceSession();
        scheduler.bindAndDrain();
        check(executed.get() == 10, "old-session task executed after session advance");
        scheduler.close();
        check(!scheduler.submit(second, executed::incrementAndGet), "closed scheduler accepted work");
    }

    private static void testClientSessionShutdown() {
        ClientSession session = new ClientSession();
        ClientSession.Observation observation = session.observe(null);
        check(observation.getState() == ClientSession.State.SUSPENDED,
            "empty client session did not enter suspended state");
        session.shutdown();
        session.shutdown();
        check(session.getState() == ClientSession.State.TERMINATED,
            "client session shutdown was not idempotent");
        check(!session.getScheduler().submit(null, () -> { }),
            "terminated client session accepted scheduled work");
    }

    private static void testEntityFrameImmutability() {
        EntityRecord original = record(41, "first");
        EntityRecord replacement = record(42, "replacement");
        EntityRecord[] source = new EntityRecord[] {original};
        EntityFrame frame = new EntityFrame(7L, 3L, source);
        check(frame.size() == 1, "entity frame changed its record count");
        check(frame.get(0) == original, "entity frame changed its owned record");
        check(frame.find(41) == original, "entity frame ID index does not match its immutable record");
        check(frame.getTickSequence() == 7L && frame.getSessionGeneration() == 3L,
            "entity frame lost generation metadata");
        List<EntityRecord> view = frame.asList();
        try {
            view.set(0, replacement);
            throw new AssertionError("entity frame exposed a mutable list");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable view.
        }
    }

    private static EntityRecord record(int id, String name) {
        return new EntityRecord(id, EntityRecord.Kind.PLAYER, name, "", "", false, true,
            false, false, true, false, 0.0D, 0.0D, 0.0D, 1.0D, 2.0D, 3.0D,
            0.0D, 0.0D, 0.0D, 0.5D, 1.0D, 2.5D, 1.5D, 3.0D, 3.5D,
            2.0D, 10.0F, 5.0F, 20.0F, 20.0F, 10, 0.1F);
    }

    private static void testPacketContracts() {
        OwnerToken owner = OwnerToken.issue("Blink", 4L);
        PacketLane outbound = new PacketLane(owner, PacketLane.Direction.OUTBOUND, "movement");
        PacketLane same = new PacketLane(owner, PacketLane.Direction.OUTBOUND, "movement");
        PacketLane inbound = new PacketLane(owner, PacketLane.Direction.INBOUND, "movement");
        check(outbound.equals(same) && outbound.hashCode() == same.hashCode(),
            "equivalent packet lanes must compare equally");
        check(!outbound.equals(inbound), "packet direction must isolate lanes");

        PacketDecision held = new PacketDecision(PacketDecision.Action.HOLD, null, owner, 80, 250,
            PacketReleasePolicy.DEADLINE, outbound);
        check(held.isHeld() && !held.isCancelled(), "hold decision semantics are incorrect");
        check(held.getOwnerToken().equals(owner), "decision lost its owner token");
        check(held.getLane().equals(outbound), "decision lost its packet lane");
        check(held.getDelayMillis() == 250, "decision lost its captured deadline");

        PacketDecision delayedCancel = new PacketDecision(PacketDecision.Action.HOLD, null, owner,
            100, 50, PacketReleasePolicy.DEADLINE, inbound, true);
        check(delayedCancel.isHeld() && delayedCancel.isCancelled()
            && delayedCancel.shouldCancelOnRelease(), "delayed cancellation was not captured atomically");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
