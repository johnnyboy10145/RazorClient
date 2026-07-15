package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import org.lwjgl.input.Keyboard;

/** Filters locally generated attacks that cannot deal damage yet. */
public final class HitSelectModule extends Module {
    private static volatile HitSelectModule instance;
    private final NumberSetting pauseDuration = new NumberSetting("Pause Duration", 0, 1000, 25, 500);
    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.BURST);
    private final BooleanSetting fakeSwing = new BooleanSetting("Fake Swing", true);
    private final NumberSetting combatCancelRate = new NumberSetting("In Combat Cancel Rate", 0, 100, 1, 100);
    private final NumberSetting missedSwingCancelRate = new NumberSetting("Missed Swings Cancel Rate", 0, 100, 1, 40);
    private final BooleanSetting disableDuringKnockback = new BooleanSetting("Disable During Knockback", false);
    private final BooleanSetting onlyWhileDamaged = new BooleanSetting("Only While Damaged", false);

    private final Random random = getScope().getRandom();
    private long blockedSinceNanos;
    private long decisionKey = Long.MIN_VALUE;
    private boolean decisionCancels;
    private boolean attackWasDown;
    private boolean suppressed;
    private String status = "Ready";

    public HitSelectModule() {
        super("Hit Select", "Filters attacks that cannot damage the current target.", Category.COMBAT, Keyboard.KEY_NONE);
        instance = this;
        addSetting(pauseDuration);
        addSetting(mode);
        addSetting(fakeSwing);
        addSetting(combatCancelRate);
        addSetting(missedSwingCancelRate);
        addSetting(disableDuringKnockback);
        addSetting(onlyWhileDamaged);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!CombatModuleSupport.inGame(minecraft) || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            resetRuntimeState();
            return;
        }

        boolean attackDown = CombatModuleSupport.attackButtonDown(minecraft);
        if (!attackDown) {
            attackWasDown = false;
            suppressed = false;
            status = "Ready";
            return;
        }

        EntityLivingBase target = CombatModuleSupport.crosshairLivingTarget(minecraft);
        suppressed = shouldSuppress(minecraft, target, System.nanoTime());
        if (suppressed) {
            minecraft.leftClickCounter = Math.max(1, minecraft.leftClickCounter);
            if (!attackWasDown && fakeSwing.isEnabled()) {
                minecraft.thePlayer.swingItem();
            }
            status = target == null ? "Miss filtered" : "Waiting";
        } else {
            status = target == null ? "Miss allowed" : "Ready to hit";
        }
        attackWasDown = true;
    }

    @Override
    public void onOutboundPacket(Packet<?> packet) {
        if (CombatModuleSupport.isAttackPacket(packet)) {
            blockedSinceNanos = 0L;
            status = "Hit sent";
        }
    }

    @Override
    protected void onDisable() {
        resetRuntimeState();
    }

    @Override
    public void onSessionReset() {
        resetRuntimeState();
    }

    @Override
    public void onInputContextLost() {
        resetRuntimeState();
    }

    @Override
    public String getHudInfo() {
        return mode.getValue() + " " + status;
    }

    /** Narrow integration hook for shared attack dispatchers. */
    public boolean shouldSuppressCurrentAttack(EntityLivingBase target) {
        Minecraft minecraft = Minecraft.getMinecraft();
        return isEnabled() && CombatModuleSupport.inGame(minecraft)
            && shouldSuppress(minecraft, target, System.nanoTime());
    }

    public static boolean isSuppressingAttack() {
        HitSelectModule module = instance;
        return module != null && module.isEnabled() && module.suppressed;
    }

    public static boolean shouldSuppressAttack(EntityLivingBase target) {
        HitSelectModule module = instance;
        return module != null && module.shouldSuppressCurrentAttack(target);
    }

    private boolean shouldSuppress(Minecraft minecraft, EntityLivingBase target, long now) {
        boolean candidate;
        int cancelRate;
        if (target == null || target == minecraft.thePlayer) {
            candidate = true;
            cancelRate = missedSwingCancelRate.getValue();
        } else {
            if (target instanceof EntityPlayer
                    && (!CombatModuleSupport.validEnemyPlayer(minecraft, target))) {
                candidate = true;
                cancelRate = 100;
            } else {
                int damageWindow = Math.max(1, target.maxHurtResistantTime / 2);
                candidate = target.hurtResistantTime > damageWindow;
                if (mode.getValue() == Mode.CRITICALS
                        && !(disableDuringKnockback.isEnabled() && minecraft.thePlayer.hurtTime > 0)
                        && (!onlyWhileDamaged.isEnabled() || minecraft.thePlayer.hurtTime > 0)) {
                    candidate |= !minecraft.thePlayer.onGround && minecraft.thePlayer.motionY > 0.0D;
                }
                cancelRate = combatCancelRate.getValue();
            }
        }

        if (!candidate || cancelRate <= 0) {
            blockedSinceNanos = 0L;
            return false;
        }

        int targetId = target == null ? -1 : target.getEntityId();
        int hurtTime = target == null ? 0 : target.hurtResistantTime;
        long currentDecisionKey = ((long) minecraft.thePlayer.ticksExisted << 32)
            ^ ((long) targetId << 16) ^ (hurtTime & 0xFFFFL);
        if (decisionKey != currentDecisionKey) {
            decisionKey = currentDecisionKey;
            decisionCancels = cancelRate >= 100 || random.nextInt(100) < cancelRate;
        }
        if (!decisionCancels) {
            blockedSinceNanos = 0L;
            return false;
        }

        if (blockedSinceNanos == 0L) {
            blockedSinceNanos = now;
        }
        long maximumPauseNanos = pauseDuration.getValue() * 1_000_000L;
        if (maximumPauseNanos > 0L && now - blockedSinceNanos >= maximumPauseNanos) {
            blockedSinceNanos = 0L;
            return false;
        }
        return true;
    }

    private void resetRuntimeState() {
        blockedSinceNanos = 0L;
        decisionKey = Long.MIN_VALUE;
        decisionCancels = false;
        attackWasDown = false;
        suppressed = false;
        status = "Ready";
    }

    public enum Mode {
        BURST("Burst"),
        CRITICALS("Criticals");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
