package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.network.PacketDelayManager;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;

public final class VelocityModule extends Module {
    private static final int MAX_CACHED_DECISIONS = 128;
    private static final long DECISION_TTL_MS = 5_000L;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.REGULAR);
    private final NumberSetting horizontal = new NumberSetting("Horizontal", 0, 100, 1, 70);
    private final NumberSetting vertical = new NumberSetting("Vertical", 0, 100, 1, 80);
    private final NumberSetting chance = new NumberSetting("Chance", 0, 100, 1, 100);
    private final BooleanSetting explosions = new BooleanSetting("Explosions", true);
    private final NumberSetting fov = new NumberSetting("FOV", 15, 360, 1, 360);
    private final NumberSetting reduceDelay = new NumberSetting("Reduce Delay", 0, 250, 5, 0);
    private final BooleanSetting onlyWhenTargeting = new BooleanSetting("Only When Targeting", false);
    private final BooleanSetting mousePressed = new BooleanSetting("Mouse Pressed", false);
    private final BooleanSetting onlyMoving = new BooleanSetting("Only Moving", true);
    private final BooleanSetting onlyOnGround = new BooleanSetting("Only On Ground", false);
    private final BooleanSetting randomize = new BooleanSetting("Randomize", true);
    private final BooleanSetting movingForward = new BooleanSetting("Moving Forward", false);
    private final BooleanSetting holdingWeapon = new BooleanSetting("Holding Weapon", false);
    private final BooleanSetting waterCheck = new BooleanSetting("Water Check", true);
    private final EnumSetting<VerticalMode> verticalMode = new EnumSetting<VerticalMode>("Vertical Mode", VerticalMode.values(), VerticalMode.NEVER);
    private final BooleanSetting requireSprinting = new BooleanSetting("Require Sprinting", false);
    private final BooleanSetting allowDoubleClicks = new BooleanSetting("Allow Double Clicks", false);

    private final Map<Packet<?>, VelocityDecision> decisions = new IdentityHashMap<Packet<?>, VelocityDecision>();
    private final Random random = getScope().getRandom();
    private volatile String status = "Ready";
    private volatile long statusUntil;
    private volatile long clientTick;
    private volatile long lastAttackTick = Long.MIN_VALUE;
    private volatile boolean doubleAttackThisTick;
    private volatile GateSnapshot gateSnapshot = GateSnapshot.EMPTY;

    public VelocityModule() {
        super("Velocity", "Adjusts or cancels local knockback response.", Category.LAG_MODULES, Keyboard.KEY_NONE);
        addSetting(mode);
        addSetting(horizontal);
        addSetting(vertical);
        addSetting(chance);
        addSetting(explosions);
        addSetting(fov);
        addSetting(reduceDelay);
        addSetting(onlyWhenTargeting);
        addSetting(mousePressed);
        addSetting(onlyMoving);
        addSetting(onlyOnGround);
        addSetting(randomize);
        addSetting(movingForward);
        addSetting(holdingWeapon);
        addSetting(waterCheck);
        addSetting(verticalMode);
        addSetting(requireSprinting);
        addSetting(allowDoubleClicks);
    }

    @Override
    protected void onDisable() {
        synchronized (decisions) { decisions.clear(); }
        status = "Ready";
        statusUntil = 0L;
        lastAttackTick = Long.MIN_VALUE;
        doubleAttackThisTick = false;
    }

    @Override
    public void onSessionReset() {
        resetRuntimeState();
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!LagModuleSupport.inGame(minecraft)) {
            resetRuntimeState();
            return;
        }
        gateSnapshot = captureGateSnapshot(minecraft);
        clientTick++;
        if (lastAttackTick != clientTick) {
            doubleAttackThisTick = false;
        }

        synchronized (decisions) {
            if (!decisions.isEmpty()) cleanupExpiredDecisions(LagModuleSupport.now());
        }

        if (statusUntil > 0L && LagModuleSupport.now() > statusUntil) {
            status = "Ready";
            statusUntil = 0L;
        }
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if (!LagModuleSupport.isAttackPacket(packet)) {
            return;
        }
        if (lastAttackTick == clientTick) {
            doubleAttackThisTick = true;
        } else {
            lastAttackTick = clientTick;
            doubleAttackThisTick = false;
        }
    }

    @Override
    public void onInboundPacket(Packet<?> packet) {
        GateSnapshot gates = gateSnapshot;
        PacketKind kind = getVelocityPacketKind(packet, gates);
        if (kind == PacketKind.NONE) {
            return;
        }
        synchronized (decisions) { if (decisions.containsKey(packet)) return; }

        if (!conditionsPass(gates) || !roll(chance.getValue())) {
            setStatus("Skipped", 900L);
            return;
        }

        VelocityDecision decision = createDecision(packet, kind, gates);
        boolean needsPostProcess = decision.mode == Mode.JUMP || decision.mode == Mode.REDUCE;
        if (decision.delayMs > 0 || decision.cancel || needsPostProcess) {
            if (!cacheDecision(packet, decision)) {
                setStatus("Failed", 1200L);
                return;
            }
        }
        if (decision.delayMs > 0) {
            setStatus("Delayed", Math.max(900L, decision.delayMs + 500L));
            return;
        }
        if (decision.cancel || needsPostProcess) {
            return;
        }

        applyDecision(packet, decision);
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        VelocityDecision decision;
        synchronized (decisions) { decision = decisions.get(packet); }
        if (decision == null) return 0;
        // Jump/Reduce must run after vanilla applies the packet. A one-millisecond
        // client-thread envelope gives the transport a reliable post-process phase.
        if (decision.delayMs <= 0 && (decision.mode == Mode.JUMP || decision.mode == Mode.REDUCE)) return 1;
        return decision.delayMs;
    }

    @Override
    public int getInboundPacketDelayPriority(Packet<?> packet) {
        synchronized (decisions) { return decisions.containsKey(packet) ? 100 : 0; }
    }

    @Override
    public void onInboundPacketReleased(Packet<?> packet) {
        VelocityDecision decision;
        synchronized (decisions) { decision = decisions.get(packet); }
        if (decision == null) {
            return;
        }
        if (decision.cancel) {
            return;
        }
        if (decision.mode == Mode.JUMP || decision.mode == Mode.REDUCE) {
            return;
        }

        synchronized (decisions) { decisions.remove(packet); }
        applyDecision(packet, decision);
    }

    @Override
    public void onInboundPacketProcessed(Packet<?> packet) {
        VelocityDecision decision;
        synchronized (decisions) { decision = decisions.remove(packet); }
        if (decision == null || decision.cancel) return;
        if (decision.mode == Mode.JUMP || decision.mode == Mode.REDUCE) {
            applyDecision(packet, decision);
        }
    }

    @Override
    public boolean shouldCancelInboundPacket(Packet<?> packet) {
        VelocityDecision decision;
        synchronized (decisions) {
            decision = decisions.get(packet);
            if (decision != null && decision.cancel) decisions.remove(packet);
        }
        if (decision == null || !decision.cancel) {
            return false;
        }

        setStatus("Cancelled", 1200L);
        return true;
    }

    @Override
    public String getHudInfo() {
        if (!"Ready".equals(status)) {
            return status;
        }
        if (mode.getValue() == Mode.REGULAR) {
            return horizontal.getValue() + "% " + vertical.getValue() + "%";
        }
        return mode.getValue().toString();
    }

    @Override
    public int getHudInfoColor() {
        if ("Cancelled".equals(status)
            || "Scaled".equals(status)
            || "Explosion".equals(status)
            || "Jump".equals(status)
            || "Reduced".equals(status)
            || "Ignored".equals(status)
            || "Delayed".equals(status)) {
            return 0xFFB07CFF;
        }
        if ("Skipped".equals(status)) {
            return 0xFFFFC05A;
        }
        if ("Failed".equals(status)) {
            return 0xFFFF5050;
        }
        return super.getHudInfoColor();
    }

    private VelocityDecision createDecision(Packet<?> packet, PacketKind kind, GateSnapshot gates) {
        Mode selectedMode = mode.getValue();
        // S27 also carries explosion/world data. Cancelling the complete packet
        // desynchronizes blocks and effects, so only local entity velocity may
        // use transport cancellation; explosions are retained with zero impulse.
        boolean cancel = kind == PacketKind.ENTITY
            && selectedMode != Mode.JUMP
            && horizontal.getValue() == 0
            && vertical.getValue() == 0;
        int appliedHorizontal = horizontal.getValue();
        int appliedVertical = vertical.getValue();
        if (selectedMode == Mode.IGNORE) {
            appliedHorizontal = 0;
            appliedVertical = ignoreVerticalPercent(gates);
        }
        if (!cancel && selectedMode != Mode.JUMP && selectedMode != Mode.REDUCE && randomize.isEnabled()) {
            int delta = random.nextInt(5) - 2;
            if (appliedHorizontal > 0) {
                appliedHorizontal = MathHelper.clamp_int(appliedHorizontal + delta, 0, 100);
            }
            if (appliedVertical > 0) {
                appliedVertical = MathHelper.clamp_int(appliedVertical + delta, 0, 100);
            }
        }
        return new VelocityDecision(
            kind,
            selectedMode,
            cancel,
            appliedHorizontal,
            appliedVertical,
            lastAttackTick == clientTick,
            doubleAttackThisTick,
            reduceDelay.getValue(),
            LagModuleSupport.now()
        );
    }

    private void applyDecision(Packet<?> packet, VelocityDecision decision) {
        if (decision.mode == Mode.JUMP) {
            runClientPlayerAction(decision);
            return;
        }

        if (decision.mode == Mode.REDUCE) {
            runClientPlayerAction(decision);
            return;
        }

        if (!LagModuleSupport.scaleLocalVelocity(packet, decision.horizontal, decision.vertical)) {
            setStatus("Failed", 1500L);
            return;
        }

        if (decision.kind == PacketKind.EXPLOSION) {
            setStatus("Explosion", 1200L);
        } else if (decision.mode == Mode.IGNORE) {
            setStatus("Ignored", 1200L);
        } else {
            setStatus("Scaled", 1200L);
        }
    }

    private void runClientPlayerAction(final VelocityDecision decision) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        Runnable action = new Runnable() {
            @Override
            public void run() {
                if (!isEnabled() || !LagModuleSupport.inGame(minecraft)) {
                    return;
                }
                if (decision.mode == Mode.JUMP) {
                    LagModuleSupport.jumpReset(minecraft);
                    setStatus("Jump", 1200L);
                } else if (applyReduce(decision)) {
                    setStatus("Reduced", 1200L);
                } else {
                    setStatus("Skipped", 900L);
                }
            }
        };
        if (getContext().isClientThread()) {
            action.run();
        } else if (!getContext().scheduleClientTask(action)) {
            setStatus("Failed", 1200L);
        }
    }

    private PacketKind getVelocityPacketKind(Packet<?> packet, GateSnapshot gates) {
        if (!gates.available) return PacketKind.NONE;
        if (packet instanceof S12PacketEntityVelocity
            && ((S12PacketEntityVelocity) packet).getEntityID() == gates.playerEntityId) {
            return PacketKind.ENTITY;
        }
        if (packet instanceof S27PacketExplosion && explosions.isEnabled()) {
            return PacketKind.EXPLOSION;
        }
        return PacketKind.NONE;
    }

    private boolean conditionsPass(GateSnapshot gates) {
        return gates.available
            && (!waterCheck.isEnabled() || !gates.inWater)
            && (!mousePressed.isEnabled() || gates.mouseDown)
            && (!onlyMoving.isEnabled() || gates.moving)
            && (!onlyOnGround.isEnabled() || gates.onGround)
            && (!requireSprinting.isEnabled() || mode.getValue() != Mode.JUMP || gates.sprinting)
            && (!movingForward.isEnabled() || gates.movingForward)
            && (!holdingWeapon.isEnabled() || gates.holdingWeapon)
            && (!onlyWhenTargeting.isEnabled() || gates.targetInFov);
    }

    private boolean roll(int percent) {
        return percent >= 100 || (percent > 0 && random.nextInt(100) < percent);
    }

    private int ignoreVerticalPercent(GateSnapshot gates) {
        switch (verticalMode.getValue()) {
            case ALWAYS:
                return 0;
            case ONLY_IN_AIR:
                return gates.onGround ? 100 : 0;
            case NEVER:
            default:
                return 100;
        }
    }

    private boolean applyReduce(VelocityDecision decision) {
        if (!decision.sameTickAttack || (decision.doubleAttack && !allowDoubleClicks.isEnabled())) {
            return false;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!LagModuleSupport.activeInGame(minecraft)) {
            return false;
        }
        minecraft.thePlayer.motionX *= 0.6D;
        minecraft.thePlayer.motionZ *= 0.6D;
        minecraft.thePlayer.setSprinting(false);
        return true;
    }

    private void setStatus(String status, long durationMs) {
        this.status = status;
        this.statusUntil = LagModuleSupport.now() + durationMs;
    }

    private boolean cacheDecision(Packet<?> packet, VelocityDecision decision) {
        long now = LagModuleSupport.now();
        synchronized (decisions) {
            if (decisions.size() >= MAX_CACHED_DECISIONS) cleanupExpiredDecisions(now);
            if (decisions.size() >= MAX_CACHED_DECISIONS) return false;
            decisions.put(packet, decision);
            return true;
        }
    }

    private void cleanupExpiredDecisions(long now) {
        PacketDelayManager transport = PacketDelayManager.getInstance();
        Iterator<Map.Entry<Packet<?>, VelocityDecision>> iterator = decisions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Packet<?>, VelocityDecision> entry = iterator.next();
            VelocityDecision decision = entry.getValue();
            boolean queued = transport != null && transport.isInboundQueued(entry.getKey());
            if (!queued && now - decision.createdAtMs > DECISION_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private void resetRuntimeState() {
        synchronized (decisions) { decisions.clear(); }
        status = "Ready";
        statusUntil = 0L;
        lastAttackTick = Long.MIN_VALUE;
        doubleAttackThisTick = false;
        gateSnapshot = GateSnapshot.EMPTY;
    }

    private GateSnapshot captureGateSnapshot(Minecraft minecraft) {
        return new GateSnapshot(
            LagModuleSupport.activeInGame(minecraft),
            minecraft.thePlayer.getEntityId(),
            minecraft.thePlayer.isInWater(),
            LagModuleSupport.mouseDown(),
            LagModuleSupport.moving(minecraft),
            minecraft.thePlayer.onGround,
            minecraft.thePlayer.isSprinting(),
            LagModuleSupport.movingForward(minecraft),
            LagModuleSupport.holdingWeapon(minecraft),
            LagModuleSupport.crosshairTarget(minecraft, 6.0D, fov.getValue()) != null
        );
    }

    private enum PacketKind {
        NONE,
        ENTITY,
        EXPLOSION
    }

    private enum Mode {
        REGULAR("Regular"),
        JUMP("Jump"),
        REDUCE("Reduce"),
        IGNORE("Ignore");

        private final String text;

        Mode(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private enum VerticalMode {
        NEVER("Never"),
        ONLY_IN_AIR("Only In Air"),
        ALWAYS("Always");

        private final String text;

        VerticalMode(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final class VelocityDecision {
        private final PacketKind kind;
        private final Mode mode;
        private final boolean cancel;
        private final int horizontal;
        private final int vertical;
        private final boolean sameTickAttack;
        private final boolean doubleAttack;
        private final int delayMs;
        private final long createdAtMs;

        private VelocityDecision(
            PacketKind kind,
            Mode mode,
            boolean cancel,
            int horizontal,
            int vertical,
            boolean sameTickAttack,
            boolean doubleAttack,
            int delayMs,
            long createdAtMs
        ) {
            this.kind = kind;
            this.mode = mode;
            this.cancel = cancel;
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.sameTickAttack = sameTickAttack;
            this.doubleAttack = doubleAttack;
            this.delayMs = delayMs;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class GateSnapshot {
        private static final GateSnapshot EMPTY = new GateSnapshot(false, -1, false, false,
            false, false, false, false, false, false);
        private final boolean available;
        private final int playerEntityId;
        private final boolean inWater;
        private final boolean mouseDown;
        private final boolean moving;
        private final boolean onGround;
        private final boolean sprinting;
        private final boolean movingForward;
        private final boolean holdingWeapon;
        private final boolean targetInFov;

        private GateSnapshot(boolean available, int playerEntityId, boolean inWater,
                boolean mouseDown, boolean moving, boolean onGround, boolean sprinting,
                boolean movingForward, boolean holdingWeapon, boolean targetInFov) {
            this.available = available;
            this.playerEntityId = playerEntityId;
            this.inWater = inWater;
            this.mouseDown = mouseDown;
            this.moving = moving;
            this.onGround = onGround;
            this.sprinting = sprinting;
            this.movingForward = movingForward;
            this.holdingWeapon = holdingWeapon;
            this.targetInFov = targetInFov;
        }
    }
}
