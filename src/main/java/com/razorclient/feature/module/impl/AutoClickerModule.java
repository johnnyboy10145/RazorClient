package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.RazorClient;
import com.razorclient.combat.CombatActionCoordinator;
import com.razorclient.util.MouseButtonHelper;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class AutoClickerModule extends Module {
    private final Random random = getScope().getRandom();
    private final Method guiClickMethod;
    private final Field leftClickCounterField;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.NORMAL);
    private final BooleanSetting breakBlocks = new BooleanSetting("Break Blocks", true);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);
    private final BooleanSetting inventoryFill = new BooleanSetting("Inventory Fill", false);
    private final DecimalSetting minCps = new DecimalSetting("Min CPS", 1.0D, 25.0D, 0.5D, 10.0D);
    private final DecimalSetting maxCps = new DecimalSetting("Max CPS", 1.0D, 25.0D, 0.5D, 14.0D);
    private final NumberSetting jitterStrength = new NumberSetting("Jitter", 0, 10, 1, 2);
    private final EnumSetting<ClickPattern> clickPattern = new EnumSetting<ClickPattern>("Click Pattern", ClickPattern.values(), ClickPattern.NORMAL);
    private final BooleanSetting randomizeSpeed = new BooleanSetting("Randomization", true);
    private final BooleanSetting simulateFatigue = new BooleanSetting("Simulated Fatigue", false);
    private final BooleanSetting notUsingItem = new BooleanSetting("Not Using Item", false);
    private final NumberSetting inventoryCps = new NumberSetting("Inventory CPS", 1, 20, 1, 10);

    private long lastClick;
    private long nextClickAt;
    private long holdUntil;
    private long recordNextClickTime;
    private int burstTicks;
    private int recordIndex;
    private boolean leftDown;
    private boolean recordNoticeShown;
    private Mode lastMode;

    public AutoClickerModule() {
        super("LeftClicker", "Automatically left-clicks for you, use mode Record for strict anticheats like Polar.", Category.COMBAT, Keyboard.KEY_NONE);
        guiClickMethod = findGuiClickMethod();
        leftClickCounterField = findLeftClickCounterField();
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
        addSetting(breakBlocks);
        addSetting(weaponOnly);
        addSetting(inventoryFill);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(jitterStrength);
        addSetting(clickPattern);
        addSetting(randomizeSpeed);
        addSetting(simulateFatigue);
        addSetting(notUsingItem);
        addSetting(inventoryCps);
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
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            resetClickState();
            return;
        }

        removeClickDelay(minecraft);
        normalizeRanges();

        if (mode.getValue() != lastMode) {
            resetClickState();
            lastMode = mode.getValue();
        }

        if (minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            doInventoryClick(minecraft);
            return;
        }

        if (!Mouse.isButtonDown(0)) {
            resetPhysicalState();
            return;
        }

        if (weaponOnly.isEnabled() && !isHoldingWeapon(minecraft)) {
            resetPhysicalState();
            return;
        }
        if (notUsingItem.isEnabled() && minecraft.thePlayer.isUsingItem()) {
            resetPhysicalState();
            return;
        }
        if (isKillAuraAttacking()) {
            resetPhysicalState();
            return;
        }

        if (breakBlock(minecraft)) {
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

        if (leftDown) {
            if (now >= holdUntil) {
                sendClick(false);
                leftDown = false;
            }
            return;
        }

        if (nextClickAt == 0L) nextClickAt = now;
        if (now < nextClickAt || !CombatActionCoordinator.tryAcquire(
                "LeftClicker", CombatModuleSupport.crosshairLivingTarget(minecraft))) return;

        long delay = computeDelayNanos();
        long holdLength = Math.max(1000000L, delay / (clickPattern.getValue() == ClickPattern.BUTTERFLY ? 4L : 2L));
        lastClick = now;
        nextClickAt = now + delay;
        holdUntil = now + holdLength;
        applyJitter(minecraft);
        sendClick(true);
        leftDown = true;
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

        if (!CombatActionCoordinator.tryAcquire(
                "LeftClicker", CombatModuleSupport.crosshairLivingTarget(minecraft))) return;
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

    private void sendClick(boolean pressed) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int key = minecraft.gameSettings.keyBindAttack.getKeyCode();
        KeyBinding.setKeyBindState(key, pressed);
        setMouseButtonState(0, pressed);
        if (pressed) {
            KeyBinding.onTick(key);
        }
    }

    private boolean breakBlock(Minecraft minecraft) {
        MovingObjectPosition hitResult = minecraft.objectMouseOver;
        if (!breakBlocks.isEnabled() || hitResult == null || hitResult.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return false;
        }

        int key = minecraft.gameSettings.keyBindAttack.getKeyCode();
        KeyBinding.setKeyBindState(key, true);
        if (leftDown) {
            MouseButtonHelper.setButton(0, false);
            leftDown = false;
        }
        return true;
    }

    private void doInventoryClick(Minecraft minecraft) {
        if (!inventoryFill.isEnabled()) {
            return;
        }

        if (!(minecraft.currentScreen instanceof GuiInventory) && !(minecraft.currentScreen instanceof GuiChest)) {
            return;
        }

        boolean shiftDown = Keyboard.isKeyDown(Keyboard.KEY_RSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_LSHIFT);
        if (!Mouse.isButtonDown(0) || !shiftDown) {
            resetClickState();
            return;
        }

        long now = System.nanoTime();
        long delay = Math.max(1L, 1000000000L / Math.max(1, inventoryCps.getValue()));
        if (now - lastClick < delay) {
            return;
        }

        lastClick = now;
        inInventoryClick(minecraft.currentScreen, minecraft);
    }

    private void inInventoryClick(GuiScreen guiScreen, Minecraft minecraft) {
        if (guiClickMethod == null || minecraft.displayWidth <= 0 || minecraft.displayHeight <= 0) {
            return;
        }
        int mouseX = Mouse.getX() * guiScreen.width / minecraft.displayWidth;
        int mouseY = guiScreen.height - Mouse.getY() * guiScreen.height / minecraft.displayHeight - 1;

        try {
            guiClickMethod.invoke(guiScreen, Integer.valueOf(mouseX), Integer.valueOf(mouseY), Integer.valueOf(0));
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }
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

    private boolean isHoldingWeapon(Minecraft minecraft) {
        if (minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }

        String name = minecraft.thePlayer.getHeldItem().getUnlocalizedName();
        return name != null && (name.contains("sword") || name.contains("axe"));
    }

    private long computeDelayNanos() {
        double min = minCps.getValue();
        double max = Math.max(min, maxCps.getValue());
        double cps = min + (random.nextDouble() * (max - min));
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

    private boolean isKillAuraAttacking() {
        RazorClient client = RazorClient.getInstance();
        KillAuraModule aura = client == null ? null : client.getModuleManager().getModule(KillAuraModule.class);
        return aura != null && aura.isActivelyAttacking();
    }

    @Override
    public String getHudInfo() {
        return clickPattern.getValue().getDisplayName() + " " + minCps.getValue() + "-" + maxCps.getValue() + " CPS";
    }

    private void setMouseButtonState(int mouseButton, boolean held) {
        MouseButtonHelper.setButton(mouseButton, held);
    }

    private void removeClickDelay(Minecraft minecraft) {
        if (leftClickCounterField == null || !minecraft.inGameHasFocus || minecraft.thePlayer.capabilities.isCreativeMode) {
            return;
        }

        try {
            leftClickCounterField.setInt(minecraft, 0);
        } catch (IllegalAccessException ignored) {
        }
    }

    private void resetClickState() {
        lastClick = 0L;
        nextClickAt = 0L;
        holdUntil = 0L;
        recordIndex = 0;
        recordNextClickTime = -1L;
        recordNoticeShown = false;
        resetPhysicalState();
    }

    private void resetPhysicalState() {
        if (leftDown) {
            MouseButtonHelper.setButton(0, false);
        }
        leftDown = false;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null && minecraft.gameSettings != null) {
            KeyBinding.setKeyBindState(
                minecraft.gameSettings.keyBindAttack.getKeyCode(),
                Mouse.isCreated() && Mouse.isButtonDown(0)
            );
        }
    }

    private void normalizeRanges() {
        if (maxCps.getValue() < minCps.getValue()) {
            maxCps.setManualValue(minCps.getValue());
        }
    }

    private float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private void sendChat(String text) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer != null) {
            minecraft.thePlayer.addChatMessage(new ChatComponentText("[AutoClicker] " + text));
        }
    }

    private Method findGuiClickMethod() {
        try {
            Method method = ReflectionHelper.findMethod(
                GuiScreen.class,
                null,
                new String[]{"func_73864_a", "mouseClicked"},
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            );
            if (method != null) {
                method.setAccessible(true);
            }
            return method;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Field findLeftClickCounterField() {
        try {
            Field field = ReflectionHelper.findField(Minecraft.class, "field_71429_W", "leftClickCounter");
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
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
