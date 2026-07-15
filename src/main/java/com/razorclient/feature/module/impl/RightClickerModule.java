package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.combat.CombatActionCoordinator;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.util.MouseButtonHelper;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class RightClickerModule extends Module {
    private final Random random = getScope().getRandom();

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.NORMAL);
    private final BooleanSetting onlyBlocks = new BooleanSetting("Only Blocks", true);
    private final NumberSetting minCps = new NumberSetting("Min CPS", 1, 25, 1, 15);
    private final NumberSetting maxCps = new NumberSetting("Max CPS", 1, 25, 1, 22);
    private final NumberSetting jitterStrength = new NumberSetting("Jitter", 0, 10, 1, 0);
    private final EnumSetting<ClickPattern> clickPattern = new EnumSetting<ClickPattern>("Click Pattern", ClickPattern.values(), ClickPattern.NORMAL);
    private final BooleanSetting randomizeSpeed = new BooleanSetting("Randomization", true);
    private final BooleanSetting simulateFatigue = new BooleanSetting("Simulated Fatigue", false);
    private final BooleanSetting notUsingItem = new BooleanSetting("Not Using Item", false);

    private long lastClick;
    private long nextClickAt;
    private long holdUntil;
    private long recordNextClickTime;
    private int burstTicks;
    private int recordIndex;
    private boolean rightDown;
    private boolean recordNoticeShown;
    private Mode lastMode;

    public RightClickerModule() {
        super("RightClicker", "Automatically right-clicks for you, use mode Record for strict anticheats like Polar.", Category.COMBAT, Keyboard.KEY_NONE);
        minCps.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() != Mode.RECORD;
            }
        });
        maxCps.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() != Mode.RECORD;
            }
        });
        addSetting(mode);
        addSetting(onlyBlocks);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(jitterStrength);
        addSetting(clickPattern);
        addSetting(randomizeSpeed);
        addSetting(simulateFatigue);
        addSetting(notUsingItem);
    }

    @Override
    protected void onEnable() {
        resetClickState();
    }

    @Override
    protected void onDisable() {
        resetClickState();
    }

    @Override
    public void onSessionReset() {
        resetClickState();
    }

    @Override
    public void onInputContextLost() {
        resetClickState();
    }

    @Override
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            resetClickState();
            return;
        }

        normalizeRanges();
        if (mode.getValue() != lastMode) {
            resetClickState();
            lastMode = mode.getValue();
        }
        if (minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            stopAutoClicking();
            return;
        }

        if (!Mouse.isButtonDown(1)) {
            resetPhysicalState();
            return;
        }

        if (onlyBlocks.isEnabled() && !isHoldingBlock(minecraft)) {
            stopAutoClicking();
            return;
        }
        if (notUsingItem.isEnabled() && minecraft.thePlayer.isUsingItem()) {
            stopAutoClicking();
            return;
        }

        if (mode.getValue() == Mode.RECORD) {
            recordClick(minecraft);
            return;
        }
        normalClick(minecraft);
    }

    private void normalClick(Minecraft minecraft) {
        long now = System.nanoTime();

        if (rightDown) {
            if (now >= holdUntil) {
                sendClick(false);
                rightDown = false;
            }
            return;
        }

        if (nextClickAt == 0L) nextClickAt = now;
        if (now < nextClickAt || !CombatActionCoordinator.tryAcquire("RightClicker")) return;

        long delay = computeDelayNanos();
        long holdLength = Math.max(1000000L, delay / (clickPattern.getValue() == ClickPattern.BUTTERFLY ? 4L : 2L));
        lastClick = now;
        nextClickAt = now + delay;
        holdUntil = now + holdLength;
        applyJitter(minecraft);
        sendClick(true);
        rightDown = true;
    }

    private void sendClick(boolean pressed) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int key = minecraft.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(key, pressed);
        setMouseButtonState(1, pressed);
        if (pressed) {
            KeyBinding.onTick(key);
        }
    }

    private void recordClick(Minecraft minecraft) {
        int delayCount = ClickPatternStore.size();
        if (delayCount == 0) {
            if (!recordNoticeShown) {
                sendChat("No recorded pattern. Use ClickRecorder in CLIENT first.");
                recordNoticeShown = true;
            }
            return;
        }

        long now = System.nanoTime();
        if (recordNextClickTime < 0L) {
            recordNextClickTime = now;
        }

        if (now < recordNextClickTime) {
            return;
        }

        if (!CombatActionCoordinator.tryAcquire("RightClicker")) return;
        applyJitter(minecraft);
        sendClick(true);
        sendClick(false);

        recordIndex++;
        if (recordIndex >= delayCount) {
            recordIndex = 0;
        }

        recordNextClickTime = now + (Math.max(0, ClickPatternStore.getDelay(recordIndex)) * 1000000L);
        recordNoticeShown = false;
    }

    private void applyJitter(Minecraft minecraft) {
        int strength = jitterStrength.getValue();
        if (strength <= 0) {
            return;
        }

        float yawDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat() * (strength * 0.45F);
        float pitchDelta = (random.nextBoolean() ? 1 : -1) * random.nextFloat() * (strength * 0.2F);
        minecraft.thePlayer.rotationYaw += yawDelta;
        minecraft.thePlayer.rotationPitch = clampPitch(minecraft.thePlayer.rotationPitch + pitchDelta);
    }

    private long computeDelayNanos() {
        int min = minCps.getValue();
        int max = Math.max(min, maxCps.getValue());
        double cps = min + (random.nextDouble() * (max - min + 1));
        if (burstTicks <= 0) {
            burstTicks = 3 + random.nextInt(9);
        }
        burstTicks--;
        if (randomizeSpeed.isEnabled()) {
            cps += random.nextGaussian() * 0.55D;
            cps += clickPattern.getValue() == ClickPattern.JITTER ? 0.35D : 0.0D;
        }
        if (simulateFatigue.isEnabled() && random.nextDouble() < 0.06D) {
            cps -= 0.8D + random.nextDouble();
        }
        cps = Math.max(1.0D, cps);
        return Math.max(1000000L, Math.round(1000000000.0D / cps));
    }

    private void resetClickState() {
        lastClick = 0L;
        nextClickAt = 0L;
        holdUntil = 0L;
        recordNextClickTime = -1L;
        recordIndex = 0;
        recordNoticeShown = false;
        resetPhysicalState();
    }

    private void stopAutoClicking() {
        boolean physicalDown = Mouse.isButtonDown(1);
        if (rightDown) {
            setMouseButtonState(1, false);
        }
        rightDown = false;
        syncUseItemKey(physicalDown);
    }

    private void resetPhysicalState() {
        if (rightDown) {
            MouseButtonHelper.setButton(1, false);
        }
        rightDown = false;
        syncUseItemKey(Mouse.isCreated() && Mouse.isButtonDown(1));
    }

    private void syncUseItemKey(boolean physicalDown) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int key = minecraft.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(key, physicalDown);
    }

    private void setMouseButtonState(int mouseButton, boolean held) {
        MouseButtonHelper.setButton(mouseButton, held);
    }

    private void normalizeRanges() {
        if (maxCps.getValue() < minCps.getValue()) {
            maxCps.setManualValue(minCps.getValue());
        }
    }

    private boolean isHoldingBlock(Minecraft minecraft) {
        return minecraft.thePlayer.getHeldItem() != null
            && minecraft.thePlayer.getHeldItem().getItem() instanceof ItemBlock;
    }

    private float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private void sendChat(String text) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) {
            minecraft.thePlayer.addChatMessage(new ChatComponentText("[RightClicker] " + text));
        }
    }

    @Override
    public String getHudInfo() {
        return clickPattern.getValue().getDisplayName() + " " + minCps.getValue() + "-" + maxCps.getValue() + " CPS";
    }

    private enum Mode {
        NORMAL,
        RECORD
    }

    private enum ClickPattern {
        NORMAL("Normal"), JITTER("Jitter"), BUTTERFLY("Butterfly");
        private final String displayName;
        ClickPattern(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }
}
