package com.razorclient.gui;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.impl.ClickGuiModule;
import com.razorclient.feature.setting.ActionSetting;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.feature.setting.Setting;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/** A retained, independently scrollable category column. */
final class ClickGuiCategoryPanel {
    private static final int HEADER_HEIGHT = 18;
    private static final int ROW_HEIGHT = 16;
    private static final long DESCRIPTION_HOVER_DELAY_NANOS = 2_000_000_000L;
    private static final String KEYBIND_LABEL = "Keybind";
    private static final float TEXT_SCALE = 0.68F;
    private static final float HEADER_TEXT_SCALE = 0.76F;

    private final Category category;
    private final List<Module> modules;
    private final GuiAnimation panelHoverAnimation = new GuiAnimation();
    private final GuiAnimation expandAnimation = new GuiAnimation();
    private final GuiAnimation scrollbarAnimation = new GuiAnimation();
    private final Map<Module, GuiAnimation> moduleHoverAnimations = new IdentityHashMap<Module, GuiAnimation>();
    private final Map<Module, GuiAnimation> moduleToggleAnimations = new IdentityHashMap<Module, GuiAnimation>();
    private final Map<Setting, GuiAnimation> settingToggleAnimations = new IdentityHashMap<Setting, GuiAnimation>();

    private String filter = "";
    private Module expandedModule;
    private Module bindingModule;
    private Module hoveredModule;
    private Module slidingModule;
    private Setting slidingSetting;
    private boolean slidingRangeLow;
    private long hoveredSince;
    private int hoveredRowY;
    private int x;
    private int y;
    private int width;
    private int scrollOffset;
    private int bodyContentHeight;
    private int maxPanelHeight = Integer.MAX_VALUE;
    private boolean contentHeightDirty = true;
    private boolean dragging;
    private boolean movedByUser;
    private int dragOffsetX;
    private int dragOffsetY;

    ClickGuiCategoryPanel(Category category, int x, int y, int width, List<Module> modules) {
        this.category = category;
        this.x = x;
        this.y = y;
        this.width = width;
        this.modules = modules;
        recomputeContentHeight();
    }

    void draw(int mouseX, int mouseY, FontRenderer fontRenderer, int screenWidth, int screenHeight, int scaleFactor) {
        recomputeContentHeight();
        int accent = ClickGuiModule.getAccentColor();
        int lightAccent = ClickGuiModule.getLightAccentColor();
        int darkAccent = ClickGuiModule.getDarkAccentColor();
        if (dragging) {
            x = mouseX - dragOffsetX;
            y = mouseY - dragOffsetY;
            clampToScreen(screenWidth, screenHeight);
        }

        clampScroll(screenHeight);
        int bodyHeight = getVisibleBodyHeight(screenHeight);
        int height = HEADER_HEIGHT + bodyHeight;
        boolean panelHovered = ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, y, width, height);
        float panelHover = panelHoverAnimation.update(panelHovered || dragging);
        float expandedProgress = expandAnimation.update(expandedModule != null);
        if (ClickGuiModule.areGuiEffectsEnabled()) {
            GuiEffects.drawGlowPanel(x, y, width, height, panelHover * 0.55F, Math.min(70, ClickGuiModule.getGlowIntensity()));
            GuiEffects.drawGradientFill(x, y, width, height, Math.round(12 + (expandedProgress * 8.0F)));
        } else {
            drawPanelShadow(x, y, width, height);
            Gui.drawRect(x, y, x + width, y + height, 0xEE0B0D13);
        }
        Gui.drawRect(x, y, x + width, y + HEADER_HEIGHT, 0xFF000000 | GuiTheme.raisedSurface());
        if (ClickGuiModule.areGuiEffectsEnabled() && ClickGuiModule.isSweepAnimationEnabled()) {
            GuiEffects.drawSweep(x, y, width, HEADER_HEIGHT, Math.max(0.12F, panelHover * 0.45F), Math.min(55, ClickGuiModule.getGlowIntensity()));
        }
        Gui.drawRect(x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, GuiTheme.withAlpha(accent, 150));
        Gui.drawRect(x, y, x + 2, y + height, 0xFF000000 | accent);
        Gui.drawRect(x + 2, y, x + width - 1, y + 1, GuiTheme.withAlpha(lightAccent, 35));
        Gui.drawRect(x + width - 1, y, x + width, y + height, GuiTheme.withAlpha(GuiTheme.border(), 210));
        Gui.drawRect(x, y + height - 1, x + width, y + height, GuiTheme.withAlpha(GuiTheme.border(), 230));
        ClickGuiRenderUtil.drawScaledString(
            fontRenderer,
            ClickGuiRenderUtil.formatCategoryName(category).toUpperCase(java.util.Locale.ROOT),
            x + 8,
            y + 5,
            0xFF000000 | GuiTheme.text(),
            true,
            HEADER_TEXT_SCALE
        );

        int bodyTop = y + HEADER_HEIGHT;
        int bodyBottom = bodyTop + bodyHeight;
        int rowY = bodyTop - scrollOffset;
        Module currentlyHoveredModule = null;
        int currentHoveredRowY = 0;
        ClickGuiRenderUtil.enableScissor(x, bodyTop, width, bodyHeight, screenHeight, scaleFactor);
        try {
            for (Module module : modules) {
                if (!isModuleVisible(module)) {
                    continue;
                }
                boolean visible = isRowVisible(rowY, bodyTop, bodyBottom);
                boolean hovered = visible
                    && ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, rowY, width, ROW_HEIGHT)
                    && isBodyHovered(mouseX, mouseY, screenHeight);
                float moduleHover = animationFor(moduleHoverAnimations, module).update(hovered);
                float moduleToggle = animationFor(moduleToggleAnimations, module).update(module.isEnabled());
                if (visible) {
                    drawModuleRow(module, rowY, hovered, moduleHover, moduleToggle, accent, lightAccent, fontRenderer);
                }

                if (hovered) {
                    currentlyHoveredModule = module;
                    currentHoveredRowY = rowY;
                }
                rowY += ROW_HEIGHT;

                if (expandedModule == module) {
                    rowY = drawSettings(module, rowY, bodyTop, bodyBottom, mouseX, mouseY, screenHeight,
                        accent, lightAccent, darkAccent, fontRenderer);
                }
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }

        updateHoveredModule(currentlyHoveredModule);
        hoveredRowY = currentHoveredRowY;
        drawScrollbar(bodyTop, bodyHeight, accent);
    }

    private void drawModuleRow(
        Module module,
        int rowY,
        boolean hovered,
        float moduleHover,
        float moduleToggle,
        int accent,
        int lightAccent,
        FontRenderer fontRenderer
    ) {
        int rowColor = GuiTheme.blendArgb(
            0xFF000000 | (module.isEnabled() ? GuiTheme.raisedSurface() : GuiTheme.surface()),
            0xFF000000 | GuiTheme.hoverSurface(),
            moduleHover
        );
        int textColor = 0xFF000000 | (module.isEnabled() ? GuiTheme.text() : GuiTheme.mutedText());
        int indicatorColor = module.isEnabled() ? (0xFF000000 | accent) : (0xFF000000 | GuiTheme.border());

        Gui.drawRect(x + 4, rowY + 1, x + width - 4, rowY + ROW_HEIGHT, rowColor);
        if (hovered) {
            Gui.drawRect(x + 5, rowY + ROW_HEIGHT - 1, x + width - 5, rowY + ROW_HEIGHT, GuiTheme.withAlpha(lightAccent, 65));
        }
        Gui.drawRect(x + 4, rowY + 1, x + 5, rowY + ROW_HEIGHT,
            module.isEnabled() ? (0xFF000000 | lightAccent) : 0x00303642);

        String suffix = module.getHudInfo();
        String moduleLabel = suffix == null || suffix.isEmpty() ? module.getName() : module.getName() + "  " + suffix;
        ClickGuiRenderUtil.drawScaledString(
            fontRenderer,
            ClickGuiRenderUtil.fitText(fontRenderer, moduleLabel, width - 39, TEXT_SCALE),
            x + 9,
            rowY + 5,
            textColor,
            false,
            TEXT_SCALE
        );
        Gui.drawRect(x + width - 18, rowY + 5, x + width - 9, rowY + 12, 0xFF000000 | GuiTheme.pageBackground());
        if (ClickGuiModule.areGuiEffectsEnabled() && module.isEnabled()) {
            Gui.drawRect(x + width - 19, rowY + 4, x + width - 8, rowY + 13, GuiTheme.withAlpha(lightAccent, 45));
        }
        int toggleWidth = Math.max(1, Math.round(7.0F * moduleToggle));
        Gui.drawRect(x + width - 17, rowY + 6, x + width - 17 + toggleWidth, rowY + 11, indicatorColor);
        ClickGuiRenderUtil.drawScaledString(fontRenderer, expandedModule == module ? "-" : "+",
            x + width - 29, rowY + 5, 0xFF000000 | accent, false, TEXT_SCALE);
        if (module.getKeyCode() != Keyboard.KEY_NONE) {
            Gui.drawRect(x + width - 36, rowY + 7, x + width - 34, rowY + 9, 0xFF000000 | lightAccent);
        }
    }

    private int drawSettings(
        Module module,
        int rowY,
        int bodyTop,
        int bodyBottom,
        int mouseX,
        int mouseY,
        int screenHeight,
        int accent,
        int lightAccent,
        int darkAccent,
        FontRenderer fontRenderer
    ) {
        for (Setting setting : module.getSettings()) {
            if (!setting.isVisible()) {
                continue;
            }
            boolean visible = isRowVisible(rowY, bodyTop, bodyBottom);
            boolean settingHovered = visible
                && ClickGuiRenderUtil.isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT)
                && isBodyHovered(mouseX, mouseY, screenHeight);
            if (visible) {
                drawSettingRow(setting, rowY, settingHovered, accent, lightAccent, darkAccent, fontRenderer);
            }
            rowY += ROW_HEIGHT;
        }

        if (module.showsKeybindSetting()) {
            boolean visible = isRowVisible(rowY, bodyTop, bodyBottom);
            boolean keyHovered = visible
                && ClickGuiRenderUtil.isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT)
                && isBodyHovered(mouseX, mouseY, screenHeight);
            if (visible) {
                drawKeybindRow(module, rowY, keyHovered, lightAccent, darkAccent, fontRenderer);
            }
            rowY += ROW_HEIGHT;
        }
        return rowY;
    }

    private void drawSettingRow(
        Setting setting,
        int rowY,
        boolean hovered,
        int accent,
        int lightAccent,
        int darkAccent,
        FontRenderer fontRenderer
    ) {
        Gui.drawRect(x + 6, rowY, x + width - 6, rowY + ROW_HEIGHT,
            0xFF000000 | (hovered ? GuiTheme.hoverSurface() : GuiTheme.raisedSurface()));
        Gui.drawRect(x + 6, rowY, x + 7, rowY + ROW_HEIGHT, 0xFF000000 | darkAccent);
        if (setting instanceof BooleanSetting) {
            boolean enabled = ((BooleanSetting) setting).isEnabled();
            float toggleProgress = animationFor(settingToggleAnimations, setting).update(enabled);
            int toggleX = x + width - 24;
            Gui.drawRect(toggleX, rowY + 5, toggleX + 14, rowY + 11,
                GuiTheme.blendArgb(0xFF000000 | GuiTheme.border(), GuiTheme.withAlpha(accent, 180), toggleProgress));
            int knobX = toggleX + 1 + Math.round(8.0F * toggleProgress);
            Gui.drawRect(knobX, rowY + 6, knobX + 4, rowY + 10,
                0xFF000000 | (enabled ? GuiTheme.text() : GuiTheme.mutedText()));
            ClickGuiRenderUtil.drawScaledString(
                fontRenderer,
                ClickGuiRenderUtil.fitText(fontRenderer, setting.getName(), toggleX - (x + 10) - 4, TEXT_SCALE),
                x + 10,
                rowY + 5,
                0xFF000000 | GuiTheme.text(),
                false,
                TEXT_SCALE
            );
        } else {
            String valueText = setting.getValueText();
            int valueWidth = Math.min(ClickGuiRenderUtil.scaledWidth(fontRenderer, valueText, TEXT_SCALE), Math.max(24, width / 2));
            String fittedValue = ClickGuiRenderUtil.fitText(fontRenderer, valueText, valueWidth, TEXT_SCALE);
            int valueX = x + width - 9 - ClickGuiRenderUtil.scaledWidth(fontRenderer, fittedValue, TEXT_SCALE);
            ClickGuiRenderUtil.drawScaledString(
                fontRenderer,
                ClickGuiRenderUtil.fitText(fontRenderer, setting.getName(), valueX - (x + 10) - 4, TEXT_SCALE),
                x + 10,
                rowY + 5,
                0xFF000000 | GuiTheme.text(),
                false,
                TEXT_SCALE
            );
            ClickGuiRenderUtil.drawScaledString(fontRenderer, fittedValue, valueX, rowY + 5,
                0xFF000000 | lightAccent, false, TEXT_SCALE);
        }
        if (ClickGuiSliderControl.supports(setting)) {
            ClickGuiSliderControl.draw(setting, x + 10, rowY + ROW_HEIGHT - 3, width - 20, accent, darkAccent);
        }
    }

    private void drawKeybindRow(
        Module module,
        int rowY,
        boolean hovered,
        int lightAccent,
        int darkAccent,
        FontRenderer fontRenderer
    ) {
        Gui.drawRect(x + 6, rowY, x + width - 6, rowY + ROW_HEIGHT,
            0xFF000000 | (hovered ? GuiTheme.hoverSurface() : GuiTheme.raisedSurface()));
        Gui.drawRect(x + 6, rowY, x + 7, rowY + ROW_HEIGHT, 0xFF000000 | darkAccent);
        ClickGuiRenderUtil.drawScaledString(fontRenderer, KEYBIND_LABEL, x + 10, rowY + 5,
            0xFF000000 | GuiTheme.text(), false, TEXT_SCALE);
        String text = bindingModule == module ? "Press key..." : getKeybindText(module);
        String fitted = ClickGuiRenderUtil.fitText(fontRenderer, text, width - 60, TEXT_SCALE);
        ClickGuiRenderUtil.drawScaledString(fontRenderer, fitted,
            x + width - 9 - ClickGuiRenderUtil.scaledWidth(fontRenderer, fitted, TEXT_SCALE),
            rowY + 5, 0xFF000000 | lightAccent, false, TEXT_SCALE);
    }

    void drawDescriptionOverlay(FontRenderer fontRenderer, int screenWidth) {
        if (shouldShowDescription(hoveredModule)) {
            drawModuleDescription(hoveredModule, hoveredRowY, fontRenderer, screenWidth);
        }
    }

    boolean mouseClicked(int mouseX, int mouseY, int mouseButton, int screenHeight) {
        if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, y, width, HEADER_HEIGHT) && mouseButton == 0) {
            dragging = true;
            movedByUser = true;
            dragOffsetX = mouseX - x;
            dragOffsetY = mouseY - y;
            return true;
        }
        if (!isBodyHovered(mouseX, mouseY, screenHeight)) {
            return false;
        }

        int rowY = y + HEADER_HEIGHT - scrollOffset;
        for (Module module : modules) {
            if (!isModuleVisible(module)) {
                continue;
            }
            if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, rowY, width, ROW_HEIGHT)) {
                if (mouseButton == 0) {
                    module.toggle();
                    invalidateContentHeight();
                    return true;
                }
                if (mouseButton == 1) {
                    expandedModule = expandedModule == module ? null : module;
                    if (expandedModule != module) {
                        bindingModule = null;
                    }
                    invalidateContentHeight();
                    recomputeContentHeight();
                    clampScroll(screenHeight);
                    return true;
                }
            }
            rowY += ROW_HEIGHT;

            if (expandedModule == module) {
                for (Setting setting : module.getSettings()) {
                    if (!setting.isVisible()) {
                        continue;
                    }
                    if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT)) {
                        handleSettingClick(setting, mouseButton, module, mouseX, mouseY, rowY);
                        invalidateContentHeight();
                        recomputeContentHeight();
                        clampScroll(screenHeight);
                        return true;
                    }
                    rowY += ROW_HEIGHT;
                }

                if (module.showsKeybindSetting()
                    && ClickGuiRenderUtil.isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT)) {
                    handleKeybindClick(module, mouseButton);
                    return true;
                }
                if (module.showsKeybindSetting()) {
                    rowY += ROW_HEIGHT;
                }
            }
        }
        return ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, y, width, getPanelHeight(screenHeight));
    }

    void mouseReleased() {
        dragging = false;
        slidingModule = null;
        slidingSetting = null;
    }

    void cancelInteraction() {
        dragging = false;
        slidingModule = null;
        slidingSetting = null;
        bindingModule = null;
        hoveredModule = null;
        hoveredSince = 0L;
    }

    void mouseDragged(int mouseX) {
        if (slidingSetting != null) {
            updateSliderValue(slidingSetting, slidingModule, mouseX);
            invalidateContentHeight();
        }
    }

    boolean scroll(int mouseX, int mouseY, int amount, int screenHeight) {
        if (!ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, y, width, getPanelHeight(screenHeight))) {
            return false;
        }
        scrollOffset = ClickGuiRenderUtil.clamp(scrollOffset + amount, 0, getMaxScroll(screenHeight));
        return true;
    }

    private void recomputeContentHeight() {
        if (!contentHeightDirty) {
            return;
        }
        int rows = 0;
        for (Module module : modules) {
            if (isModuleVisible(module)) {
                rows++;
            }
        }
        if (expandedModule != null && isModuleVisible(expandedModule)) {
            for (Setting setting : expandedModule.getSettings()) {
                if (setting.isVisible()) {
                    rows++;
                }
            }
            if (expandedModule.showsKeybindSetting()) {
                rows++;
            }
        }
        bodyContentHeight = rows * ROW_HEIGHT;
        contentHeightDirty = false;
    }

    void invalidateContentHeight() {
        contentHeightDirty = true;
    }

    private int getVisibleBodyHeight(int screenHeight) {
        int screenBody = Math.max(0, screenHeight - y - HEADER_HEIGHT - 2);
        int layoutBody = maxPanelHeight == Integer.MAX_VALUE
            ? Integer.MAX_VALUE : Math.max(0, maxPanelHeight - HEADER_HEIGHT);
        int maxVisibleBody = Math.min(screenBody, layoutBody);
        return Math.min(bodyContentHeight, maxVisibleBody);
    }

    private int getPanelHeight(int screenHeight) {
        return HEADER_HEIGHT + getVisibleBodyHeight(screenHeight);
    }

    private int getMaxScroll(int screenHeight) {
        return Math.max(0, bodyContentHeight - getVisibleBodyHeight(screenHeight));
    }

    private void clampScroll(int screenHeight) {
        scrollOffset = ClickGuiRenderUtil.clamp(scrollOffset, 0, getMaxScroll(screenHeight));
    }

    void setFilter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals(filter)) {
            return;
        }
        filter = normalized;
        scrollOffset = 0;
        if (expandedModule != null && !isModuleVisible(expandedModule)) {
            bindingModule = null;
        }
        invalidateContentHeight();
        recomputeContentHeight();
    }

    private boolean isModuleVisible(Module module) {
        if (filter.isEmpty()) {
            return true;
        }
        String name = module.getName() == null ? "" : module.getName().toLowerCase(java.util.Locale.ROOT);
        String description = module.getDescription() == null ? "" : module.getDescription().toLowerCase(java.util.Locale.ROOT);
        return name.contains(filter) || description.contains(filter);
    }

    private void handleSettingClick(Setting setting, int mouseButton, Module module, int mouseX, int mouseY, int rowY) {
        if (ClickGuiSliderControl.supports(setting)) {
            if (mouseButton == 0 && isSliderHit(mouseX, mouseY, rowY)) {
                slidingModule = module;
                slidingSetting = setting;
                slidingRangeLow = ClickGuiSliderControl.isLowHandleClosest(setting, mouseX, x + 10, width - 20);
                updateSliderValue(setting, module, mouseX);
            }
            return;
        }
        if (setting instanceof ActionSetting && mouseButton == 0) {
            ((ActionSetting) setting).run();
            return;
        }
        if (setting instanceof BooleanSetting && mouseButton == 0) {
            ((BooleanSetting) setting).toggle();
            return;
        }
        if (setting instanceof EnumSetting && (mouseButton == 0 || mouseButton == 1)) {
            EnumSetting<?> enumSetting = (EnumSetting<?>) setting;
            if (mouseButton == 0) {
                enumSetting.cycleForward();
            } else {
                enumSetting.cycleBackward();
            }
        }
    }

    private boolean isSliderHit(int mouseX, int mouseY, int rowY) {
        return ClickGuiRenderUtil.isHovered(mouseX, mouseY, x + 8, rowY + ROW_HEIGHT - 7, width - 16, 8);
    }

    private void updateSliderValue(Setting setting, Module module, int mouseX) {
        int sliderX = x + 10;
        int sliderWidth = width - 20;
        ClickGuiSliderControl.update(setting, mouseX, sliderX, sliderWidth, slidingRangeLow);
        if (setting instanceof NumberSetting) {
            enforceNumberBounds(module);
        }
    }

    private void enforceNumberBounds(Module module) {
        NumberSetting min = null;
        NumberSetting max = null;
        for (Setting setting : module.getSettings()) {
            if (!(setting instanceof NumberSetting)) {
                continue;
            }
            if ("Min CPS".equals(setting.getName())) {
                min = (NumberSetting) setting;
            } else if ("Max CPS".equals(setting.getName())) {
                max = (NumberSetting) setting;
            }
        }
        if (min != null && max != null && max.getValue() < min.getValue()) {
            max.setManualValue(min.getValue());
        }
    }

    private void handleKeybindClick(Module module, int mouseButton) {
        if (mouseButton == 0) {
            bindingModule = module;
        } else if (mouseButton == 1 && module.canBeUnbound()) {
            module.setKeyCode(Keyboard.KEY_NONE);
            bindingModule = null;
        }
    }

    boolean handleKeyTyped(int keyCode) {
        if (bindingModule == null) {
            return false;
        }
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) {
            if (bindingModule.canBeUnbound()) {
                bindingModule.setKeyCode(Keyboard.KEY_NONE);
            }
        } else {
            bindingModule.setKeyCode(keyCode);
        }
        bindingModule = null;
        return true;
    }

    private String getKeybindText(Module module) {
        if (module.getKeyCode() == Keyboard.KEY_NONE) {
            return "NONE";
        }
        String name = Keyboard.getKeyName(module.getKeyCode());
        return name == null ? "UNKNOWN" : name.toUpperCase(java.util.Locale.ROOT);
    }

    private void updateHoveredModule(Module module) {
        long now = System.nanoTime();
        if (module != hoveredModule) {
            hoveredModule = module;
            hoveredSince = module == null ? 0L : now;
        }
    }

    private boolean shouldShowDescription(Module module) {
        return module != null
            && module.getDescription() != null
            && !module.getDescription().isEmpty()
            && System.nanoTime() - hoveredSince >= DESCRIPTION_HOVER_DELAY_NANOS;
    }

    private void drawModuleDescription(Module module, int rowY, FontRenderer fontRenderer, int screenWidth) {
        String description = module.getDescription();
        int padding = 6;
        int tooltipWidth = ClickGuiRenderUtil.scaledWidth(fontRenderer, description, TEXT_SCALE) + (padding * 2);
        int tooltipX = x + (width - tooltipWidth) / 2;
        int tooltipY = rowY - ROW_HEIGHT - 7;
        tooltipX = Math.max(4, Math.min(Math.max(4, screenWidth - tooltipWidth - 4), tooltipX));
        if (tooltipY < 4) {
            tooltipY = rowY + 2;
        }
        Gui.drawRect(tooltipX + 2, tooltipY + 2, tooltipX + tooltipWidth + 2,
            tooltipY + ROW_HEIGHT + 2, 0x90000000);
        Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + ROW_HEIGHT,
            GuiTheme.withAlpha(GuiTheme.raisedSurface(), 240));
        Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + 1,
            0xFF000000 | ClickGuiModule.getAccentColor());
        ClickGuiRenderUtil.drawScaledString(fontRenderer, description, tooltipX + padding, tooltipY + 4,
            0xFF000000 | GuiTheme.text(), true, TEXT_SCALE);
    }

    void setWidth(int width) {
        this.width = width;
    }

    void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    void setMaxPanelHeight(int maxPanelHeight) {
        this.maxPanelHeight = Math.max(HEADER_HEIGHT, maxPanelHeight);
    }

    void clearMaxPanelHeight() {
        this.maxPanelHeight = Integer.MAX_VALUE;
    }

    boolean wasMovedByUser() {
        return movedByUser;
    }

    void clampToScreen(int screenWidth, int screenHeight) {
        recomputeContentHeight();
        int maxX = Math.max(2, screenWidth - width - 2);
        int maxY = Math.max(ClickGuiLayoutModel.TOP_MARGIN, screenHeight - getPanelHeight(screenHeight) - 2);
        x = Math.max(2, Math.min(maxX, x));
        y = Math.max(ClickGuiLayoutModel.TOP_MARGIN, Math.min(maxY, y));
        clampScroll(screenHeight);
    }

    private void drawPanelShadow(int x, int y, int panelWidth, int panelHeight) {
        Gui.drawRect(x + 2, y + 2, x + panelWidth + 2, y + panelHeight + 2, 0x66000000);
        Gui.drawRect(x + 1, y + 1, x + panelWidth + 1, y + panelHeight + 1, 0x44000000);
    }

    private boolean isBodyHovered(int mouseX, int mouseY, int screenHeight) {
        return ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, y + HEADER_HEIGHT, width, getVisibleBodyHeight(screenHeight));
    }

    private boolean isRowVisible(int rowY, int bodyTop, int bodyBottom) {
        return rowY + ROW_HEIGHT > bodyTop && rowY < bodyBottom;
    }

    private void drawScrollbar(int bodyTop, int bodyHeight, int accent) {
        if (bodyContentHeight <= bodyHeight || bodyHeight <= 0) {
            return;
        }
        int trackX = x + width - 3;
        Gui.drawRect(trackX, bodyTop + 2, trackX + 1, bodyTop + bodyHeight - 2, 0x66000000);
        int thumbHeight = Math.max(10, (bodyHeight * bodyHeight) / Math.max(1, bodyContentHeight));
        int maxScroll = Math.max(1, bodyContentHeight - bodyHeight);
        int thumbTravel = Math.max(1, bodyHeight - thumbHeight - 4);
        float targetProgress = scrollOffset / (float) maxScroll;
        int thumbY = bodyTop + 2 + Math.round(scrollbarAnimation.update(targetProgress, 90L) * thumbTravel);
        int color = ClickGuiModule.areGuiEffectsEnabled()
            ? GuiTheme.withAlpha(ClickGuiModule.getLightAccentColor(), 170)
            : (0xFF000000 | accent);
        Gui.drawRect(trackX, thumbY, trackX + 1, thumbY + thumbHeight, color);
    }

    private <T> GuiAnimation animationFor(Map<T, GuiAnimation> animations, T key) {
        GuiAnimation animation = animations.get(key);
        if (animation == null) {
            animation = new GuiAnimation();
            animations.put(key, animation);
        }
        return animation;
    }
}
