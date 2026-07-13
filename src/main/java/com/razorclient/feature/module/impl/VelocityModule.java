package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;

public final class VelocityModule extends Module {
    private static final int MAX_CACHED_DECISIONS = 128;
    private static final long DECISION_TTL_MS = 30_000L;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.REGULAR);
    private final NumberSetting horizontal = new NumberSetting("Horizontal", 0, 100, 1, 90);
    private final NumberSetting vertical = new NumberSetting("Vertical", 0, 100, 1, 100);
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
    private final Random random = new Random();
    private volatile String status = "Ready";
    private volatile long statusUntil;
    private volatile long clientTick;
    private volatile long lastAttackTick = Long.MIN_VALUE;
    private volatile boolean doubleAttackThisTick;

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
        Minecraft minecraft = Minecraft.getMinecraft();
        PacketKind kind = getVelocityPacketKind(packet, minecraft);
        if (kind == PacketKind.NONE) {
            return;
        }
        synchronized (decisions) { if (decisions.containsKey(packet)) return; }

        if (!conditionsPass(minecraft) || !roll(chance.getValue())) {
            setStatus("Skipped", 900L);
            return;
        }

        VelocityDecision decision = createDecision(packet, kind, minecraft);
        if (decision.delayMs > 0) {
            cacheDecision(packet, decision);
            setStatus("Delayed", Math.max(900L, decision.delayMs + 500L));
            return;
        }

        if (decision.cancel) {
            cacheDecision(packet, decision);
            return;
        }

        applyDecision(packet, decision);
    }

    @Override
    public int getInboundPacketDelay(Packet<?> packet) {
        VelocityDecision decision;
        synchronized (decisions) { decision = decisions.get(packet); }
        return decision == null ? 0 : decision.delayMs;
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

        synchronized (decisions) { decisions.remove(packet); }
        applyDecision(packet, decision);
    }

    @Override
    public boolean shouldCancelInboundPacket(Packet<?> packet) {
        VelocityDecision decision;
        synchronized (decisions) { decision = decisions.remove(packet); }
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

    private VelocityDecision createDecision(Packet<?> packet, PacketKind kind, Minecraft minecraft) {
        Mode selectedMode = mode.getValue();
        boolean cancel = selectedMode != Mode.JUMP && horizontal.getValue() == 0 && vertical.getValue() == 0;
        int appliedHorizontal = horizontal.getValue();
        int appliedVertical = vertical.getValue();
        if (selectedMode == Mode.IGNORE) {
            appliedHorizontal = 0;
            appliedVertical = ignoreVerticalPercent(minecraft);
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
            LagModuleSupport.jumpReset(Minecraft.getMinecraft());
            setStatus("Jump", 1200L);
            return;
        }

        if (decision.mode == Mode.REDUCE) {
            if (!applyReduce(decision)) {
                setStatus("Skipped", 900L);
                return;
            }
            setStatus("Reduced", 1200L);
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

    private PacketKind getVelocityPacketKind(Packet<?> packet, Minecraft minecraft) {
        if (!LagModuleSupport.inGame(minecraft)) {
            return PacketKind.NONE;
        }
        if (packet instanceof S12PacketEntityVelocity
            && ((S12PacketEntityVelocity) packet).getEntityID() == minecraft.thePlayer.getEntityId()) {
            return PacketKind.ENTITY;
        }
        if (packet instanceof S27PacketExplosion && explosions.isEnabled()) {
            return PacketKind.EXPLOSION;
        }
        return PacketKind.NONE;
    }

    private boolean conditionsPass(Minecraft minecraft) {
        if (!LagModuleSupport.activeInGame(minecraft)) {
            return false;
        }
        if (waterCheck.isEnabled() && minecraft.thePlayer.isInWater()) {
            return false;
        }
        if (mousePressed.isEnabled() && !LagModuleSupport.mouseDown()) {
            return false;
        }
        if (onlyMoving.isEnabled() && !LagModuleSupport.moving(minecraft)) {
            return false;
        }
        if (onlyOnGround.isEnabled() && !minecraft.thePlayer.onGround) {
            return false;
        }
        if (requireSprinting.isEnabled() && mode.getValue() == Mode.JUMP && !minecraft.thePlayer.isSprinting()) {
            return false;
        }
        if (movingForward.isEnabled() && !LagModuleSupport.movingForward(minecraft)) {
            return false;
        }
        if (holdingWeapon.isEnabled() && !LagModuleSupport.holdingWeapon(minecraft)) {
            return false;
        }
        if (onlyWhenTargeting.isEnabled()) {
            EntityPlayer target = LagModuleSupport.crosshairTarget(minecraft, 6.0D, fov.getValue());
            return target != null;
        }
        return true;
    }

    private boolean roll(int percent) {
        return percent >= 100 || (percent > 0 && random.nextInt(100) < percent);
    }

    private int ignoreVerticalPercent(Minecraft minecraft) {
        switch (verticalMode.getValue()) {
            case ALWAYS:
                return 0;
            case ONLY_IN_AIR:
                return minecraft.thePlayer.onGround ? 100 : 0;
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

    private void cacheDecision(Packet<?> packet, VelocityDecision decision) {
        long now = LagModuleSupport.now();
        synchronized (decisions) {
            if (decisions.size() >= MAX_CACHED_DECISIONS) cleanupExpiredDecisions(now);
            if (decisions.size() >= MAX_CACHED_DECISIONS) decisions.clear();
            decisions.put(packet, decision);
        }
    }

    private void cleanupExpiredDecisions(long now) {
        Iterator<VelocityDecision> iterator = decisions.values().iterator();
        while (iterator.hasNext()) {
            VelocityDecision decision = iterator.next();
            if (now - decision.createdAtMs > DECISION_TTL_MS) {
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
}
