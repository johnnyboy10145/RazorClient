package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.ActionSetting;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.gui.GuiEffects;
import com.razorclient.gui.GuiTheme;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class HudModule extends Module {
    private static final int DEFAULT_X = 4;
    private static final int DEFAULT_Y = 4;
    private static final int COMPONENT_PADDING = 4;
    private static final int HEADER_HEIGHT = 12;
    private static HudModule instance;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.MODERN);
    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 255);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 255);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 255);
    private final NumberSetting x = new NumberSetting("X", 0, 4000, 2, DEFAULT_X);
    private final NumberSetting y = new NumberSetting("Y", 0, 4000, 2, DEFAULT_Y);

    private final BooleanSetting textEnabled = new BooleanSetting("Text GUI", true);
    private final BooleanSetting textPinned = new BooleanSetting("Text GUI Pinned", true);
    private final NumberSetting textScale = new NumberSetting("Text GUI Scale", 50, 200, 5, 100);
    private final EnumSetting<TextSort> textSort = new EnumSetting<TextSort>("Text GUI Sort", TextSort.values(), TextSort.LENGTH);
    private final EnumSetting<SuffixMode> suffixMode = new EnumSetting<SuffixMode>("Suffix Mode", SuffixMode.values(), SuffixMode.EXTENDED);
    private final EnumSetting<ColorMode> textColorMode = new EnumSetting<ColorMode>("Text GUI Color", ColorMode.values(), ColorMode.GUI);
    private final BooleanSetting textShadow = new BooleanSetting("Text GUI Shadow", true);
    private final BooleanSetting textBackground = new BooleanSetting("Text GUI Background", false);
    private final BooleanSetting watermark = new BooleanSetting("Watermark", false);
    private final BooleanSetting clickDisable = new BooleanSetting("Click Disable", false);

    private final BooleanSetting targetEnabled = new BooleanSetting("Target Info", true);
    private final BooleanSetting targetPinned = new BooleanSetting("Target Info Pinned", false);
    private final NumberSetting targetX = new NumberSetting("Target Info X", 0, 4000, 2, 8);
    private final NumberSetting targetY = new NumberSetting("Target Info Y", 0, 4000, 2, 80);
    private final NumberSetting targetScale = new NumberSetting("Target Info Scale", 50, 200, 5, 100);
    private final BooleanSetting targetHovered = new BooleanSetting("Target Show Hovered", true);
    private final BooleanSetting targetBackground = new BooleanSetting("Target Background", true);

    private final BooleanSetting radarEnabled = new BooleanSetting("Radar", true);
    private final BooleanSetting radarPinned = new BooleanSetting("Radar Pinned", false);
    private final NumberSetting radarX = new NumberSetting("Radar X", 0, 4000, 2, 8);
    private final NumberSetting radarY = new NumberSetting("Radar Y", 0, 4000, 2, 140);
    private final NumberSetting radarScale = new NumberSetting("Radar Scale", 50, 200, 5, 100);
    private final NumberSetting radarSize = new NumberSetting("Radar Size", 60, 180, 2, 92);
    private final NumberSetting radarRange = new NumberSetting("Radar Range", 10, 160, 5, 60);
    private final BooleanSetting radarBackground = new BooleanSetting("Radar Background", true);
    private final BooleanSetting radarCross = new BooleanSetting("Radar Cross", true);
    private final BooleanSetting radarClamp = new BooleanSetting("Radar Clamp", true);
    private final EnumSetting<ColorMode> radarColorMode = new EnumSetting<ColorMode>("Radar Color", ColorMode.values(), ColorMode.GUI);

    private final ActionSetting editor = new ActionSetting("Move HUD", new Runnable() {
        @Override
        public void run() {
            RazorClient client = RazorClient.getInstance();
            if (client != null) {
                client.openHudEditor();
            }
        }
    }, new ActionSetting.ValueProvider() {
        @Override
        public String get() {
            return "OPEN";
        }
    });

    private final List<OverlayComponent> components = new ArrayList<OverlayComponent>();
    private OverlayComponent draggingComponent;
    private int dragOffsetX;
    private int dragOffsetY;
    private EntityLivingBase lastTarget;
    private int lastTargetId = -1;
    private float lastTargetHealth;
    private int targetCombo;
    private int targetHitsTaken;

    public HudModule() {
        super("HUD", "Displays draggable overlays on screen.", Category.RENDER, Keyboard.KEY_NONE);
        instance = this;
        hideLegacyMode();
        addSettings();
        components.add(new TextGuiComponent());
        components.add(new TargetInfoComponent());
        components.add(new RadarComponent());
    }

    @Override
    public void onClientTick() {
        updateTargetTracker(Minecraft.getMinecraft());
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings.showDebugInfo) {
            return;
        }
        renderOverlays(event.resolution, false, -1, -1);
    }

    public void renderEditorOverlays(ScaledResolution resolution, int mouseX, int mouseY) {
        renderOverlays(resolution, true, mouseX, mouseY);
    }

    public void updateEditorDrag(int mouseX, int mouseY, ScaledResolution resolution) {
        if (draggingComponent == null) {
            return;
        }
        draggingComponent.setPosition(mouseX - dragOffsetX, mouseY - dragOffsetY, resolution);
    }

    public void editorMouseClicked(int mouseX, int mouseY, int mouseButton, ScaledResolution resolution) {
        if (mouseButton != 0) {
            return;
        }

        List<OverlayComponent> renderList = getRenderOrder();
        for (int i = renderList.size() - 1; i >= 0; i--) {
            OverlayComponent component = renderList.get(i);
            if (!component.isEnabled()) {
                continue;
            }

            Bounds bounds = component.getBounds(resolution, true);
            if (component.pinBounds(bounds).contains(mouseX, mouseY)) {
                component.togglePinned();
                return;
            }

            if (component.handleEditorClick(mouseX, mouseY, bounds)) {
                return;
            }

            if (bounds.contains(mouseX, mouseY)) {
                draggingComponent = component;
                dragOffsetX = mouseX - component.getX();
                dragOffsetY = mouseY - component.getY();
                return;
            }
        }
    }

    public void editorMouseReleased() {
        draggingComponent = null;
    }

    public static HudModule getInstance() {
        return instance;
    }

    public int getAnchorX() {
        return x.getValue();
    }

    public int getAnchorY() {
        return y.getValue();
    }

    public void setPosition(int x, int y) {
        this.x.setValue(x);
        this.y.setValue(y);
    }

    private void renderOverlays(ScaledResolution resolution, boolean editorOpen, int mouseX, int mouseY) {
        for (OverlayComponent component : getRenderOrder()) {
            if (!component.shouldRender(editorOpen)) {
                continue;
            }

            Bounds bounds = component.getBounds(resolution, editorOpen);
            component.renderScaled(resolution, editorOpen);
            if (editorOpen) {
                drawEditorBox(component, bounds, mouseX, mouseY);
            }
        }
    }

    private List<OverlayComponent> getRenderOrder() {
        return components;
    }

    private void drawEditorBox(OverlayComponent component, Bounds bounds, int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean hovered = bounds.contains(mouseX, mouseY);
        int accent = ClickGuiModule.getAccentColor();
        int outline = hovered || component == draggingComponent ? 0xFF000000 | ClickGuiModule.getLightAccentColor() : 0xFF6F7484;
        if (ClickGuiModule.areGuiEffectsEnabled()) {
            GuiEffects.drawGlowPanel(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top, hovered ? 0.75F : 0.25F, ClickGuiModule.getGlowIntensity());
            if (ClickGuiModule.isSweepAnimationEnabled()) {
                GuiEffects.drawSweep(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top, hovered ? 0.35F : 0.12F, ClickGuiModule.getGlowIntensity());
            }
        }
        Gui.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, 0x30262B3E);
        Gui.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + 1, outline);
        Gui.drawRect(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, outline);
        Gui.drawRect(bounds.left, bounds.top, bounds.left + 1, bounds.bottom, outline);
        Gui.drawRect(bounds.right - 1, bounds.top, bounds.right, bounds.bottom, outline);

        Bounds pin = component.pinBounds(bounds);
        int pinColor = component.isPinned() ? (0xFF000000 | accent) : 0xFF151821;
        Gui.drawRect(pin.left, pin.top, pin.right, pin.bottom, pinColor);
        if (ClickGuiModule.areGuiEffectsEnabled() && component.isPinned()) {
            Gui.drawRect(pin.left - 1, pin.top - 1, pin.right + 1, pin.bottom + 1, GuiTheme.withAlpha(ClickGuiModule.getLightAccentColor(), 40));
        }
        minecraft.fontRendererObj.drawString(component.isPinned() ? "\u2713" : "P", pin.left + 4, pin.top + 3, 0xFFFFFFFF);
        minecraft.fontRendererObj.drawStringWithShadow(component.getName(), bounds.left + 4, bounds.top - 10, 0xFFE6EAF3);
    }

    private void updateTargetTracker(Minecraft minecraft) {
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null) {
            lastTarget = null;
            lastTargetId = -1;
            lastTargetHealth = 0.0F;
            targetCombo = 0;
            targetHitsTaken = 0;
            return;
        }

        EntityLivingBase current = resolveCurrentTarget(minecraft);
        if (current == null) {
            return;
        }

        if (current.getEntityId() != lastTargetId) {
            lastTarget = current;
            lastTargetId = current.getEntityId();
            lastTargetHealth = current.getHealth();
            targetCombo = 0;
            targetHitsTaken = 0;
            return;
        }

        float health = current.getHealth();
        if (health < lastTargetHealth - 0.05F) {
            targetCombo++;
        } else if (health > lastTargetHealth + 0.05F) {
            targetHitsTaken++;
        }
        lastTargetHealth = health;
        lastTarget = current;
    }

    private EntityLivingBase resolveCurrentTarget(Minecraft minecraft) {
        if (targetHovered.isEnabled()) {
            EntityLivingBase hovered = getHoveredLiving(minecraft);
            if (hovered != null) {
                return hovered;
            }
        }
        if (lastTarget != null && !lastTarget.isDead && lastTarget.getHealth() > 0.0F && minecraft.theWorld.loadedEntityList.contains(lastTarget)) {
            return lastTarget;
        }
        return null;
    }

    private EntityLivingBase getHoveredLiving(Minecraft minecraft) {
        MovingObjectPosition mouseOver = minecraft.objectMouseOver;
        if (mouseOver == null || mouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) {
            return null;
        }
        Entity entity = mouseOver.entityHit;
        if (!(entity instanceof EntityLivingBase) || entity == minecraft.thePlayer || entity.isDead) {
            return null;
        }
        if (entity instanceof EntityPlayer && AntiBotModule.shouldIgnore((EntityPlayer) entity)) {
            return null;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        return living.getHealth() <= 0.0F ? null : living;
    }

    private List<String> getEnabledModuleNames() {
        List<String> moduleNames = new ArrayList<String>();
        RazorClient client = RazorClient.getInstance();
        if (client == null) {
            return moduleNames;
        }

        for (Module module : client.getModuleManager().getModules()) {
            if (!module.isEnabled() || module == this) {
                continue;
            }
            String hudInfo = module.getHudInfo();
            if (suffixMode.getValue() == SuffixMode.NONE || hudInfo == null || hudInfo.isEmpty()) {
                moduleNames.add(module.getName());
            } else if (suffixMode.getValue() == SuffixMode.BASIC) {
                moduleNames.add(module.getName() + " " + hudInfo);
            } else {
                moduleNames.add(module.getName() + " [" + hudInfo + "]");
            }
        }

        sortText(moduleNames);
        return moduleNames;
    }

    private void sortText(final List<String> moduleNames) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (textSort.getValue() == TextSort.ALPHABETICAL) {
            Collections.sort(moduleNames, String.CASE_INSENSITIVE_ORDER);
            return;
        }
        Collections.sort(moduleNames, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return minecraft.fontRendererObj.getStringWidth(right) - minecraft.fontRendererObj.getStringWidth(left);
            }
        });
    }

    private int getLegacyCustomColor() {
        return 0xFF000000 | ((red.getValue() & 255) << 16) | ((green.getValue() & 255) << 8) | (blue.getValue() & 255);
    }

    private int resolveColor(ColorMode colorMode, int index) {
        if (colorMode == ColorMode.CUSTOM) {
            return getLegacyCustomColor();
        }
        if (colorMode == ColorMode.MODULE) {
            double time = System.currentTimeMillis() / 320.0D;
            float wave = (float) ((Math.sin(time + (index * 0.45D)) + 1.0D) * 0.5D);
            return 0xFF000000 | ClickGuiModule.blendColor(ClickGuiModule.getLightAccentColor(), ClickGuiModule.getDarkAccentColor(), wave);
        }
        return 0xFF000000 | ClickGuiModule.getAccentColor();
    }

    private void addSettings() {
        addSetting(mode);
        addSetting(red);
        addSetting(green);
        addSetting(blue);
        addSetting(x);
        addSetting(y);
        addSetting(textEnabled);
        addSetting(textPinned);
        addSetting(textScale);
        addSetting(textSort);
        addSetting(suffixMode);
        addSetting(textColorMode);
        addSetting(textShadow);
        addSetting(textBackground);
        addSetting(watermark);
        addSetting(clickDisable);
        addSetting(targetEnabled);
        addSetting(targetPinned);
        addSetting(targetX);
        addSetting(targetY);
        addSetting(targetScale);
        addSetting(targetHovered);
        addSetting(targetBackground);
        addSetting(radarEnabled);
        addSetting(radarPinned);
        addSetting(radarX);
        addSetting(radarY);
        addSetting(radarScale);
        addSetting(radarSize);
        addSetting(radarRange);
        addSetting(radarBackground);
        addSetting(radarCross);
        addSetting(radarClamp);
        addSetting(radarColorMode);
        addSetting(editor);
    }

    private void hideLegacyMode() {
        mode.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return false;
            }
        });
        red.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return textColorMode.getValue() == ColorMode.CUSTOM || radarColorMode.getValue() == ColorMode.CUSTOM;
            }
        });
        green.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return textColorMode.getValue() == ColorMode.CUSTOM || radarColorMode.getValue() == ColorMode.CUSTOM;
            }
        });
        blue.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return textColorMode.getValue() == ColorMode.CUSTOM || radarColorMode.getValue() == ColorMode.CUSTOM;
            }
        });
    }

    private abstract class OverlayComponent {
        private final String name;

        private OverlayComponent(String name) {
            this.name = name;
        }

        private String getName() {
            return name;
        }

        private boolean shouldRender(boolean editorOpen) {
            return isEnabled() && (editorOpen || isPinned());
        }

        private Bounds getBounds(ScaledResolution resolution, boolean editorOpen) {
            int width = Math.max(24, Math.round(getLogicalWidth() * getScale()));
            int height = Math.max(14, Math.round(getLogicalHeight() * getScale()));
            int left = Math.max(0, Math.min(getX(), Math.max(0, resolution.getScaledWidth() - width)));
            int top = Math.max(0, Math.min(getY(), Math.max(0, resolution.getScaledHeight() - height)));
            return new Bounds(left - COMPONENT_PADDING, top - COMPONENT_PADDING, left + width + COMPONENT_PADDING, top + height + COMPONENT_PADDING);
        }

        private void setPosition(int x, int y, ScaledResolution resolution) {
            int width = Math.max(24, Math.round(getLogicalWidth() * getScale()));
            int height = Math.max(14, Math.round(getLogicalHeight() * getScale()));
            setX(Math.max(0, Math.min(x, Math.max(0, resolution.getScaledWidth() - width))));
            setY(Math.max(0, Math.min(y, Math.max(0, resolution.getScaledHeight() - height))));
        }

        private void renderScaled(ScaledResolution resolution, boolean editorOpen) {
            float scale = getScale();
            GL11.glPushMatrix();
            GL11.glScalef(scale, scale, 1.0F);
            render((int) (getX() / scale), (int) (getY() / scale), editorOpen);
            GL11.glPopMatrix();
        }

        private Bounds pinBounds(Bounds bounds) {
            return new Bounds(bounds.right - 16, bounds.top + 2, bounds.right - 3, bounds.top + 14);
        }

        protected boolean handleEditorClick(int mouseX, int mouseY, Bounds bounds) {
            return false;
        }

        protected abstract boolean isEnabled();

        protected abstract boolean isPinned();

        protected abstract void togglePinned();

        protected abstract int getX();

        protected abstract int getY();

        protected abstract void setX(int value);

        protected abstract void setY(int value);

        protected abstract float getScale();

        protected abstract int getLogicalWidth();

        protected abstract int getLogicalHeight();

        protected abstract void render(int x, int y, boolean editorOpen);
    }

    private final class TextGuiComponent extends OverlayComponent {
        private TextGuiComponent() {
            super("Text GUI");
        }

        @Override
        protected boolean isEnabled() {
            return textEnabled.isEnabled();
        }

        @Override
        protected boolean isPinned() {
            return textPinned.isEnabled();
        }

        @Override
        protected void togglePinned() {
            textPinned.toggle();
        }

        @Override
        protected int getX() {
            return x.getValue();
        }

        @Override
        protected int getY() {
            return y.getValue();
        }

        @Override
        protected void setX(int value) {
            x.setValue(value);
        }

        @Override
        protected void setY(int value) {
            y.setValue(value);
        }

        @Override
        protected float getScale() {
            return textScale.getValue() / 100.0F;
        }

        @Override
        protected int getLogicalWidth() {
            Minecraft minecraft = Minecraft.getMinecraft();
            int width = watermark.isEnabled() ? minecraft.fontRendererObj.getStringWidth("\u00AE\uFE0FazorClient") : 0;
            for (String moduleName : getPreviewModuleNames()) {
                width = Math.max(width, minecraft.fontRendererObj.getStringWidth(moduleName));
            }
            return Math.max(64, width + (textBackground.isEnabled() ? 8 : 0));
        }

        @Override
        protected int getLogicalHeight() {
            Minecraft minecraft = Minecraft.getMinecraft();
            int lines = getPreviewModuleNames().size() + (watermark.isEnabled() ? 1 : 0);
            return Math.max(HEADER_HEIGHT, lines * (minecraft.fontRendererObj.FONT_HEIGHT + 2));
        }

        @Override
        protected void render(int x, int y, boolean editorOpen) {
            Minecraft minecraft = Minecraft.getMinecraft();
            FontRenderer fontRenderer = minecraft.fontRendererObj;
            List<String> lines = editorOpen ? getPreviewModuleNames() : getEnabledModuleNames();
            int lineHeight = fontRenderer.FONT_HEIGHT + 2;
            int width = getLogicalWidth();
            int lineY = y;

            if (textBackground.isEnabled()) {
                Gui.drawRect(x - 3, y - 3, x + width + 3, y + getLogicalHeight() + 3, 0x7A080A10);
                Gui.drawRect(x - 3, y - 3, x - 1, y + getLogicalHeight() + 3, 0xFF000000 | ClickGuiModule.getAccentColor());
            }

            if (watermark.isEnabled()) {
                drawText(fontRenderer, "\u00AE\uFE0FazorClient", x, lineY, 0xFF000000 | ClickGuiModule.getAccentColor(), textShadow.isEnabled());
                lineY += lineHeight;
            }

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                drawText(fontRenderer, line, x, lineY, resolveColor(textColorMode.getValue(), i), textShadow.isEnabled());
                lineY += lineHeight;
            }
        }

        @Override
        protected boolean handleEditorClick(int mouseX, int mouseY, Bounds bounds) {
            if (!clickDisable.isEnabled()) {
                return false;
            }

            Minecraft minecraft = Minecraft.getMinecraft();
            int logicalY = Math.round((mouseY - getY()) / getScale());
            int lineHeight = minecraft.fontRendererObj.FONT_HEIGHT + 2;
            int index = logicalY / Math.max(1, lineHeight);
            if (watermark.isEnabled()) {
                index--;
            }
            if (index < 0) {
                return false;
            }

            List<Module> enabledModules = getEnabledModulesForClick();
            if (index >= enabledModules.size()) {
                return false;
            }
            enabledModules.get(index).setEnabled(false);
            return true;
        }

        private List<String> getPreviewModuleNames() {
            List<String> moduleNames = getEnabledModuleNames();
            if (moduleNames.isEmpty()) {
                moduleNames.add("KillAura [17.0]");
                moduleNames.add("AutoClicker [12]");
                moduleNames.add("Sprint");
                sortText(moduleNames);
            }
            return moduleNames;
        }

        private List<Module> getEnabledModulesForClick() {
            List<Module> modules = new ArrayList<Module>();
            RazorClient client = RazorClient.getInstance();
            if (client == null) {
                return modules;
            }

            for (Module module : client.getModuleManager().getModules()) {
                if (module.isEnabled() && module != HudModule.this) {
                    modules.add(module);
                }
            }
            if (textSort.getValue() == TextSort.ALPHABETICAL) {
                Collections.sort(modules, new Comparator<Module>() {
                    @Override
                    public int compare(Module left, Module right) {
                        return left.getName().compareToIgnoreCase(right.getName());
                    }
                });
            } else {
                final FontRenderer fontRenderer = Minecraft.getMinecraft().fontRendererObj;
                Collections.sort(modules, new Comparator<Module>() {
                    @Override
                    public int compare(Module left, Module right) {
                        return fontRenderer.getStringWidth(right.getName()) - fontRenderer.getStringWidth(left.getName());
                    }
                });
            }
            return modules;
        }
    }

    private final class TargetInfoComponent extends OverlayComponent {
        private TargetInfoComponent() {
            super("Target Info");
        }

        @Override
        protected boolean isEnabled() {
            return targetEnabled.isEnabled();
        }

        @Override
        protected boolean isPinned() {
            return targetPinned.isEnabled();
        }

        @Override
        protected void togglePinned() {
            targetPinned.toggle();
        }

        @Override
        protected int getX() {
            return targetX.getValue();
        }

        @Override
        protected int getY() {
            return targetY.getValue();
        }

        @Override
        protected void setX(int value) {
            targetX.setValue(value);
        }

        @Override
        protected void setY(int value) {
            targetY.setValue(value);
        }

        @Override
        protected float getScale() {
            return targetScale.getValue() / 100.0F;
        }

        @Override
        protected int getLogicalWidth() {
            return 128;
        }

        @Override
        protected int getLogicalHeight() {
            return 48;
        }

        @Override
        protected void render(int x, int y, boolean editorOpen) {
            Minecraft minecraft = Minecraft.getMinecraft();
            EntityLivingBase target = editorOpen ? null : resolveCurrentTarget(minecraft);
            String name = target == null ? "Target" : target.getName();
            float health = target == null ? 17.0F : Math.max(0.0F, target.getHealth());
            float maxHealth = target == null ? 20.0F : Math.max(1.0F, target.getMaxHealth());
            int armor = target == null ? 0 : target.getTotalArmorValue();
            int distance = target == null || minecraft.thePlayer == null ? 0 : Math.round(minecraft.thePlayer.getDistanceToEntity(target));
            int accent = ClickGuiModule.getAccentColor();

            if (targetBackground.isEnabled()) {
                Gui.drawRect(x, y, x + getLogicalWidth(), y + getLogicalHeight(), 0xD0080A10);
                Gui.drawRect(x, y, x + 2, y + getLogicalHeight(), 0xFF000000 | accent);
                Gui.drawRect(x + 2, y, x + getLogicalWidth(), y + 1, 0x35FFFFFF);
            }

            FontRenderer font = minecraft.fontRendererObj;
            drawText(font, fitText(font, name, 112), x + 7, y + 6, 0xFFFFFFFF, true);
            drawText(font, Math.round(health) + " HP  " + armor + " ARM  " + distance + "m", x + 7, y + 18, 0xFFB8BECF, false);
            Gui.drawRect(x + 7, y + 31, x + 121, y + 36, 0xFF151821);
            int healthWidth = Math.round(114.0F * Math.max(0.0F, Math.min(1.0F, health / maxHealth)));
            Gui.drawRect(x + 7, y + 31, x + 7 + healthWidth, y + 36, 0xFF000000 | accent);
            drawText(font, "Combo " + targetCombo + "  Hits " + targetHitsTaken, x + 7, y + 39, 0xFFE6EAF3, false);
        }
    }

    private final class RadarComponent extends OverlayComponent {
        private RadarComponent() {
            super("Radar");
        }

        @Override
        protected boolean isEnabled() {
            return radarEnabled.isEnabled();
        }

        @Override
        protected boolean isPinned() {
            return radarPinned.isEnabled();
        }

        @Override
        protected void togglePinned() {
            radarPinned.toggle();
        }

        @Override
        protected int getX() {
            return radarX.getValue();
        }

        @Override
        protected int getY() {
            return radarY.getValue();
        }

        @Override
        protected void setX(int value) {
            radarX.setValue(value);
        }

        @Override
        protected void setY(int value) {
            radarY.setValue(value);
        }

        @Override
        protected float getScale() {
            return radarScale.getValue() / 100.0F;
        }

        @Override
        protected int getLogicalWidth() {
            return radarSize.getValue();
        }

        @Override
        protected int getLogicalHeight() {
            return radarSize.getValue();
        }

        @Override
        protected void render(int x, int y, boolean editorOpen) {
            Minecraft minecraft = Minecraft.getMinecraft();
            int size = radarSize.getValue();
            int half = size / 2;
            int accent = ClickGuiModule.getAccentColor();

            if (radarBackground.isEnabled()) {
                Gui.drawRect(x, y, x + size, y + size, 0xC0080A10);
                Gui.drawRect(x, y, x + size, y + 1, 0x35FFFFFF);
                Gui.drawRect(x, y, x + 1, y + size, 0xFF000000 | accent);
            }
            if (radarCross.isEnabled()) {
                Gui.drawRect(x + half, y + 4, x + half + 1, y + size - 4, 0x55333A4D);
                Gui.drawRect(x + 4, y + half, x + size - 4, y + half + 1, 0x55333A4D);
            }

            Gui.drawRect(x + half - 1, y + half - 1, x + half + 2, y + half + 2, 0xFFFFFFFF);

            if (minecraft.thePlayer == null || minecraft.theWorld == null) {
                drawRadarDot(x + half + 16, y + half - 12, resolveColor(radarColorMode.getValue(), 1));
                drawRadarDot(x + half - 20, y + half + 9, resolveColor(radarColorMode.getValue(), 2));
                return;
            }

            int index = 0;
            for (Object object : minecraft.theWorld.playerEntities) {
                if (!(object instanceof EntityPlayer)) {
                    continue;
                }

                EntityPlayer player = (EntityPlayer) object;
                if (player == minecraft.thePlayer || player.isDead || player.isInvisible() || AntiBotModule.shouldIgnore(player)) {
                    continue;
                }

                double dx = player.posX - minecraft.thePlayer.posX;
                double dz = player.posZ - minecraft.thePlayer.posZ;
                double distance = Math.sqrt((dx * dx) + (dz * dz));
                if (distance > radarRange.getValue() && !radarClamp.isEnabled()) {
                    continue;
                }

                double yaw = Math.toRadians(MathHelper.wrapAngleTo180_float(minecraft.thePlayer.rotationYaw));
                double sin = Math.sin(yaw);
                double cos = Math.cos(yaw);
                double rotatedX = (dx * cos) - (dz * sin);
                double rotatedZ = (dx * sin) + (dz * cos);
                double scale = (half - 6) / (double) Math.max(1, radarRange.getValue());
                int dotX = x + half + (int) Math.round(rotatedX * scale);
                int dotY = y + half + (int) Math.round(rotatedZ * scale);

                if (radarClamp.isEnabled()) {
                    dotX = Math.max(x + 3, Math.min(x + size - 4, dotX));
                    dotY = Math.max(y + 3, Math.min(y + size - 4, dotY));
                }

                drawRadarDot(dotX, dotY, resolveColor(radarColorMode.getValue(), index++));
            }
        }

        private void drawRadarDot(int x, int y, int color) {
            Gui.drawRect(x - 1, y - 1, x + 2, y + 2, color);
        }
    }

    private static void drawText(FontRenderer fontRenderer, String text, int x, int y, int color, boolean shadow) {
        if (shadow) {
            fontRenderer.drawStringWithShadow(text, x, y, color);
        } else {
            fontRenderer.drawString(text, x, y, color);
        }
    }

    private static String fitText(FontRenderer fontRenderer, String text, int maxWidth) {
        if (text == null || maxWidth <= 0) {
            return "";
        }
        if (fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        String fitted = text;
        while (fitted.length() > 0 && fontRenderer.getStringWidth(fitted) + fontRenderer.getStringWidth(suffix) > maxWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted + suffix;
    }

    private static final class Bounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
    }

    private enum Mode {
        MODERN("Modern"),
        CLASSIC("Classic");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum TextSort {
        LENGTH("Length"),
        ALPHABETICAL("Alphabetical");

        private final String label;

        TextSort(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum SuffixMode {
        EXTENDED("Extended"),
        BASIC("Basic"),
        NONE("None");

        private final String label;

        SuffixMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum ColorMode {
        MODULE("Module Color"),
        GUI("Match GUI Color"),
        CUSTOM("Custom Color");

        private final String label;

        ColorMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
