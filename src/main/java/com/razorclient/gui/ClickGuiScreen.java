package com.razorclient.gui;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.ModuleManager;
import com.razorclient.feature.module.impl.ClickGuiModule;
import com.razorclient.feature.setting.ActionSetting;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.IntRangeSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.feature.setting.Setting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public final class ClickGuiScreen extends GuiScreen {
    private static final int MIN_PANEL_WIDTH = 94;
    private static final int MAX_PANEL_WIDTH = 112;
    private static final int SIDE_MARGIN = 8;
    private static final int TOP_MARGIN = 30;

    private final List<CategoryPanel> panels = new ArrayList<CategoryPanel>();
    private boolean layoutInitialized;
    private int lastScreenWidth;
    private int lastScreenHeight;

    public ClickGuiScreen(ModuleManager moduleManager) {
        for (Category category : Category.values()) {
            panels.add(new CategoryPanel(category, SIDE_MARGIN, TOP_MARGIN, MAX_PANEL_WIDTH, moduleManager.getModules(category)));
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        GuiCursor.enterGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        updatePanelLayout();
        drawRazorBackground();
        drawBrandHeader();

        for (CategoryPanel panel : panels) {
            panel.draw(mouseX, mouseY, fontRendererObj, this.width, this.height);
        }

        for (CategoryPanel panel : panels) {
            panel.drawDescriptionOverlay(fontRendererObj, this.width);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        GuiCursor.draw(mouseX, mouseY);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        GuiCursor.exitGui();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (int i = panels.size() - 1; i >= 0; i--) {
            if (panels.get(i).mouseClicked(mouseX, mouseY, mouseButton, this.height)) {
                return;
            }
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        for (CategoryPanel panel : panels) {
            if (panel.handleKeyTyped(keyCode)) {
                return;
            }
        }

        if (handleCloseKeybind(keyCode)) {
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        for (CategoryPanel panel : panels) {
            panel.mouseReleased();
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        for (CategoryPanel panel : panels) {
            panel.mouseDragged(mouseX);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }

        int mouseX = Mouse.getEventX() * this.width / Math.max(1, this.mc.displayWidth);
        int mouseY = this.height - Mouse.getEventY() * this.height / Math.max(1, this.mc.displayHeight) - 1;
        int amount = wheel > 0 ? -12 : 12;
        for (int i = panels.size() - 1; i >= 0; i--) {
            if (panels.get(i).scroll(mouseX, mouseY, amount, this.height)) {
                return;
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private boolean handleCloseKeybind(int keyCode) {
        if (keyCode == Keyboard.KEY_NONE) {
            return false;
        }

        ClickGuiModule clickGuiModule = ClickGuiModule.getInstance();
        if (clickGuiModule == null || keyCode != clickGuiModule.getKeyCode()) {
            return false;
        }

        RazorClient client = RazorClient.getInstance();
        if (client == null) {
            return false;
        }

        client.toggleClickGui();
        return true;
    }

    private void updatePanelLayout() {
        boolean resized = this.width != lastScreenWidth || this.height != lastScreenHeight;
        if (!layoutInitialized || resized) {
            int count = Math.max(1, panels.size());
            int gap = this.width < 640 ? 4 : 6;
            int available = Math.max(MIN_PANEL_WIDTH * count, this.width - (SIDE_MARGIN * 2) - (gap * (count - 1)));
            int panelWidth = clamp(available / count, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
            int totalWidth = (panelWidth * count) + (gap * (count - 1));
            int startX = Math.max(4, (this.width - totalWidth) / 2);

            for (int i = 0; i < panels.size(); i++) {
                CategoryPanel panel = panels.get(i);
                panel.setWidth(panelWidth);
                if (!panel.wasMovedByUser() || !layoutInitialized) {
                    panel.setPosition(startX + (i * (panelWidth + gap)), TOP_MARGIN);
                }
                panel.clampToScreen(this.width, this.height);
            }

            layoutInitialized = true;
            lastScreenWidth = this.width;
            lastScreenHeight = this.height;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void drawRazorBackground() {
        Gui.drawRect(0, 0, this.width, this.height, 0xE8050805);
        Gui.drawRect(0, 0, this.width, 24, 0xF5080D09);
        Gui.drawRect(0, 24, this.width, 25, GuiTheme.withAlpha(ClickGuiModule.getAccentColor(), 80));
        Gui.drawRect(0, this.height - 1, this.width, this.height, GuiTheme.withAlpha(ClickGuiModule.getDarkAccentColor(), 70));
    }

    private void drawBrandHeader() {
        int accent = ClickGuiModule.getAccentColor();
        int logoX = Math.max(10, (this.width - Math.min(this.width - 16, 720)) / 2);
        drawHexMark(logoX, 7, accent);
        this.fontRendererObj.drawString("RAZOR", logoX + 17, 8, 0xFFECEFEA);
        this.fontRendererObj.drawString("CLIENT", logoX + 47, 8, 0xFF000000 | accent);
        this.fontRendererObj.drawString("EXHIBIT A - MODULE CONTROL", logoX, 31, 0xFF6F7A73);
    }

    private void drawHexMark(int x, int y, int accent) {
        int dim = GuiTheme.withAlpha(accent, 95);
        Gui.drawRect(x + 4, y, x + 10, y + 1, dim);
        Gui.drawRect(x + 2, y + 1, x + 4, y + 2, dim);
        Gui.drawRect(x + 10, y + 1, x + 12, y + 2, dim);
        Gui.drawRect(x + 1, y + 2, x + 2, y + 8, dim);
        Gui.drawRect(x + 12, y + 2, x + 13, y + 8, dim);
        Gui.drawRect(x + 2, y + 8, x + 4, y + 9, dim);
        Gui.drawRect(x + 10, y + 8, x + 12, y + 9, dim);
        Gui.drawRect(x + 4, y + 9, x + 10, y + 10, dim);
        Gui.drawRect(x + 4, y + 4, x + 10, y + 6, GuiTheme.withAlpha(accent, 55));
    }

    private static final class CategoryPanel {
        private static final int HEADER_HEIGHT = 18;
        private static final int ROW_HEIGHT = 16;
        private static final long DESCRIPTION_HOVER_DELAY_MS = 2000L;
        private static final String KEYBIND_LABEL = "Keybind";
        private static final float TEXT_SCALE = 0.82F;
        private static final float HEADER_TEXT_SCALE = 0.86F;

        private final Category category;
        private final List<Module> modules;
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
        private boolean dragging;
        private boolean movedByUser;
        private int dragOffsetX;
        private int dragOffsetY;
        private final GuiAnimation panelHoverAnimation = new GuiAnimation();
        private final GuiAnimation expandAnimation = new GuiAnimation();

        private CategoryPanel(Category category, int x, int y, int width, List<Module> modules) {
            this.category = category;
            this.x = x;
            this.y = y;
            this.width = width;
            this.modules = modules;
        }

        private void draw(int mouseX, int mouseY, net.minecraft.client.gui.FontRenderer fontRenderer, int screenWidth, int screenHeight) {
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
            boolean panelHovered = isHovered(mouseX, mouseY, x, y, width, height);
            float panelHover = panelHoverAnimation.update(panelHovered || dragging);
            float expandedProgress = expandAnimation.update(expandedModule != null);
            if (ClickGuiModule.areGuiEffectsEnabled()) {
                GuiEffects.drawGlowPanel(x, y, width, height, panelHover, ClickGuiModule.getGlowIntensity());
                GuiEffects.drawGradientFill(x, y, width, height, Math.round(18 + (expandedProgress * 12.0F)));
            } else {
                drawPanelShadow(x, y, width, height);
                Gui.drawRect(x, y, x + width, y + height, 0xEE0B0D13);
            }
            Gui.drawRect(x, y, x + width, y + HEADER_HEIGHT, 0xFF0B120D);
            if (ClickGuiModule.areGuiEffectsEnabled() && ClickGuiModule.isSweepAnimationEnabled()) {
                GuiEffects.drawSweep(x, y, width, HEADER_HEIGHT, Math.max(0.25F, panelHover), ClickGuiModule.getGlowIntensity());
            }
            Gui.drawRect(x, y + HEADER_HEIGHT - 1, x + width, y + HEADER_HEIGHT, GuiTheme.withAlpha(accent, 150));
            Gui.drawRect(x, y, x + 2, y + height, 0xFF000000 | accent);
            Gui.drawRect(x + 2, y, x + width - 1, y + 1, GuiTheme.withAlpha(lightAccent, 35));
            Gui.drawRect(x + width - 1, y, x + width, y + height, GuiTheme.withAlpha(GuiTheme.border(), 210));
            Gui.drawRect(x, y + height - 1, x + width, y + height, GuiTheme.withAlpha(GuiTheme.border(), 230));
            drawScaledString(fontRenderer, formatCategoryName(category).toUpperCase(), x + 8, y + 5, 0xFFE9EEE9, true, HEADER_TEXT_SCALE);

            int bodyTop = y + HEADER_HEIGHT;
            int bodyBottom = bodyTop + bodyHeight;
            int rowY = bodyTop - scrollOffset;
            Module currentlyHoveredModule = null;
            int currentHoveredRowY = 0;
            enableScissor(x, bodyTop, width, bodyHeight, screenHeight);
            try {
                for (Module module : modules) {
                    boolean visible = isRowVisible(rowY, bodyTop, bodyBottom);
                    boolean hovered = visible && isHovered(mouseX, mouseY, x, rowY, width, ROW_HEIGHT) && isBodyHovered(mouseX, mouseY, screenHeight);
                    if (visible) {
                        int rowColor = hovered
                            ? (ClickGuiModule.areGuiEffectsEnabled() ? 0xFF132319 : 0xFF111B14)
                            : 0xFF0A100C;
                        int textColor = module.isEnabled() ? 0xFFE9EEE9 : 0xFF7F8A82;
                        int indicatorColor = module.isEnabled() ? (0xFF000000 | accent) : 0xFF1F3527;

                        Gui.drawRect(x + 4, rowY + 1, x + width - 4, rowY + ROW_HEIGHT, rowColor);
                        if (ClickGuiModule.areGuiEffectsEnabled() && hovered && ClickGuiModule.isSweepAnimationEnabled()) {
                            GuiEffects.drawSweep(x + 4, rowY + 1, width - 8, ROW_HEIGHT - 1, 0.55F, ClickGuiModule.getGlowIntensity());
                        }
                        Gui.drawRect(x + 4, rowY + 1, x + 5, rowY + ROW_HEIGHT, module.isEnabled() ? (0xFF000000 | lightAccent) : 0x00303642);
                        drawScaledString(fontRenderer, fitText(fontRenderer, module.getName(), width - 42, TEXT_SCALE), x + 9, rowY + 5, textColor, false, TEXT_SCALE);
                        Gui.drawRect(x + width - 18, rowY + 5, x + width - 9, rowY + 12, 0xFF0A0C12);
                        if (ClickGuiModule.areGuiEffectsEnabled() && module.isEnabled()) {
                            Gui.drawRect(x + width - 19, rowY + 4, x + width - 8, rowY + 13, GuiTheme.withAlpha(lightAccent, 45));
                        }
                        Gui.drawRect(x + width - 17, rowY + 6, x + width - 10, rowY + 11, indicatorColor);
                        drawScaledString(fontRenderer, expandedModule == module ? "-" : "+", x + width - 29, rowY + 5, 0xFF000000 | accent, false, TEXT_SCALE);
                    }

                    if (hovered) {
                        currentlyHoveredModule = module;
                        currentHoveredRowY = rowY;
                    }
                    rowY += ROW_HEIGHT;

                    if (expandedModule == module) {
                        for (Setting setting : module.getSettings()) {
                            if (!setting.isVisible()) {
                                continue;
                            }
                            visible = isRowVisible(rowY, bodyTop, bodyBottom);
                            boolean settingHovered = visible && isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT) && isBodyHovered(mouseX, mouseY, screenHeight);
                            if (visible) {
                                Gui.drawRect(x + 6, rowY, x + width - 6, rowY + ROW_HEIGHT, settingHovered ? 0xFF16261B : 0xFF0E1711);
                                if (ClickGuiModule.areGuiEffectsEnabled() && settingHovered && ClickGuiModule.isSweepAnimationEnabled()) {
                                    GuiEffects.drawSweep(x + 6, rowY, width - 12, ROW_HEIGHT, 0.35F, ClickGuiModule.getGlowIntensity());
                                }
                                Gui.drawRect(x + 6, rowY, x + 7, rowY + ROW_HEIGHT, 0xFF000000 | darkAccent);
                                String valueText = setting.getValueText();
                                int valueWidth = Math.min(scaledWidth(fontRenderer, valueText, TEXT_SCALE), Math.max(24, width / 2));
                                String fittedValue = fitText(fontRenderer, valueText, valueWidth, TEXT_SCALE);
                                int valueX = x + width - 9 - scaledWidth(fontRenderer, fittedValue, TEXT_SCALE);
                                drawScaledString(fontRenderer, fitText(fontRenderer, setting.getName(), valueX - (x + 10) - 4, TEXT_SCALE), x + 10, rowY + 5, 0xFFDCE5DE, false, TEXT_SCALE);
                                drawScaledString(fontRenderer, fittedValue, valueX, rowY + 5, 0xFF000000 | lightAccent, false, TEXT_SCALE);
                                if (isSliderSetting(setting)) {
                                    drawSlider(setting, x + 10, rowY + ROW_HEIGHT - 3, width - 20, accent, darkAccent);
                                }
                            }
                            rowY += ROW_HEIGHT;
                        }

                        if (module.showsKeybindSetting()) {
                            visible = isRowVisible(rowY, bodyTop, bodyBottom);
                            boolean keyHovered = visible && isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT) && isBodyHovered(mouseX, mouseY, screenHeight);
                            if (visible) {
                                Gui.drawRect(x + 6, rowY, x + width - 6, rowY + ROW_HEIGHT, keyHovered ? 0xFF16261B : 0xFF0E1711);
                                if (ClickGuiModule.areGuiEffectsEnabled() && keyHovered && ClickGuiModule.isSweepAnimationEnabled()) {
                                    GuiEffects.drawSweep(x + 6, rowY, width - 12, ROW_HEIGHT, 0.35F, ClickGuiModule.getGlowIntensity());
                                }
                                Gui.drawRect(x + 6, rowY, x + 7, rowY + ROW_HEIGHT, 0xFF000000 | darkAccent);
                                drawScaledString(fontRenderer, KEYBIND_LABEL, x + 10, rowY + 5, 0xFFDCE5DE, false, TEXT_SCALE);
                                String keybindText = bindingModule == module ? "Press key..." : getKeybindText(module);
                                String fittedKeybind = fitText(fontRenderer, keybindText, width - 60, TEXT_SCALE);
                                drawScaledString(fontRenderer, fittedKeybind, x + width - 9 - scaledWidth(fontRenderer, fittedKeybind, TEXT_SCALE), rowY + 5, 0xFF000000 | lightAccent, false, TEXT_SCALE);
                            }
                            rowY += ROW_HEIGHT;
                        }
                    }
                }
            } finally {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }

            updateHoveredModule(currentlyHoveredModule);
            hoveredRowY = currentHoveredRowY;
            drawScrollbar(bodyTop, bodyHeight, accent);
        }

        private void drawDescriptionOverlay(net.minecraft.client.gui.FontRenderer fontRenderer, int screenWidth) {
            if (shouldShowDescription(hoveredModule)) {
                drawModuleDescription(hoveredModule, hoveredRowY, fontRenderer, screenWidth);
            }
        }

        private boolean mouseClicked(int mouseX, int mouseY, int mouseButton, int screenHeight) {
            if (isHovered(mouseX, mouseY, x, y, width, HEADER_HEIGHT) && mouseButton == 0) {
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
                if (isHovered(mouseX, mouseY, x, rowY, width, ROW_HEIGHT)) {
                    if (mouseButton == 0) {
                        module.toggle();
                        return true;
                    }
                    if (mouseButton == 1) {
                        expandedModule = expandedModule == module ? null : module;
                        if (expandedModule != module) {
                            bindingModule = null;
                        }
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
                        if (isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT)) {
                            handleSettingClick(setting, mouseButton, module, mouseX, mouseY, rowY);
                            return true;
                        }
                        rowY += ROW_HEIGHT;
                    }

                    if (module.showsKeybindSetting() && isHovered(mouseX, mouseY, x + 6, rowY, width - 12, ROW_HEIGHT)) {
                        handleKeybindClick(module, mouseButton);
                        return true;
                    }
                    if (module.showsKeybindSetting()) {
                        rowY += ROW_HEIGHT;
                    }
                }
            }
            return isHovered(mouseX, mouseY, x, y, width, getPanelHeight(screenHeight));
        }

        private void mouseReleased() {
            dragging = false;
            slidingModule = null;
            slidingSetting = null;
        }

        private void mouseDragged(int mouseX) {
            if (slidingSetting == null) {
                return;
            }

            updateSliderValue(slidingSetting, slidingModule, mouseX);
        }

        private boolean scroll(int mouseX, int mouseY, int amount, int screenHeight) {
            if (!isHovered(mouseX, mouseY, x, y, width, getPanelHeight(screenHeight))) {
                return false;
            }
            scrollOffset = clamp(scrollOffset + amount, 0, getMaxScroll(screenHeight));
            return true;
        }

        private int getBodyContentHeight() {
            int rows = modules.size();
            if (expandedModule != null) {
                for (Setting setting : expandedModule.getSettings()) {
                    if (setting.isVisible()) {
                        rows++;
                    }
                }
                if (expandedModule.showsKeybindSetting()) {
                    rows++;
                }
            }
            return rows * ROW_HEIGHT;
        }

        private int getVisibleBodyHeight(int screenHeight) {
            int maxVisibleBody = Math.max(ROW_HEIGHT, screenHeight - y - HEADER_HEIGHT - 2);
            return Math.min(getBodyContentHeight(), maxVisibleBody);
        }

        private int getPanelHeight(int screenHeight) {
            return HEADER_HEIGHT + getVisibleBodyHeight(screenHeight);
        }

        private int getMaxScroll(int screenHeight) {
            return Math.max(0, getBodyContentHeight() - getVisibleBodyHeight(screenHeight));
        }

        private void clampScroll(int screenHeight) {
            scrollOffset = clamp(scrollOffset, 0, getMaxScroll(screenHeight));
        }

        private void handleSettingClick(Setting setting, int mouseButton, Module module, int mouseX, int mouseY, int rowY) {
            if (isSliderSetting(setting)) {
                if (mouseButton == 0 && isSliderHit(mouseX, mouseY, rowY)) {
                    slidingModule = module;
                    slidingSetting = setting;
                    slidingRangeLow = shouldDragLowRangeHandle(setting, mouseX);
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

        private boolean isSliderSetting(Setting setting) {
            return setting instanceof NumberSetting
                || setting instanceof DecimalSetting
                || setting instanceof IntRangeSetting;
        }

        private boolean isSliderHit(int mouseX, int mouseY, int rowY) {
            return isHovered(mouseX, mouseY, x + 8, rowY + ROW_HEIGHT - 7, width - 16, 8);
        }

        private void drawSlider(Setting setting, int sliderX, int sliderY, int sliderWidth, int accent, int darkAccent) {
            int trackY = sliderY;
            Gui.drawRect(sliderX, trackY, sliderX + sliderWidth, trackY + 2, 0xFF070A10);

            if (setting instanceof IntRangeSetting) {
                IntRangeSetting range = (IntRangeSetting) setting;
                float lowProgress = progress(range.getLow(), range.getMin(), range.getMax());
                float highProgress = progress(range.getHigh(), range.getMin(), range.getMax());
                int lowX = sliderX + Math.round(sliderWidth * lowProgress);
                int highX = sliderX + Math.round(sliderWidth * highProgress);
                Gui.drawRect(lowX, trackY, highX, trackY + 2, 0xFF000000 | accent);
                Gui.drawRect(lowX - 1, trackY - 2, lowX + 2, trackY + 4, 0xFF000000 | darkAccent);
                Gui.drawRect(highX - 1, trackY - 2, highX + 2, trackY + 4, 0xFF000000 | accent);
                return;
            }

            float progress;
            if (setting instanceof NumberSetting) {
                NumberSetting number = (NumberSetting) setting;
                progress = progress(number.getValue(), number.getMin(), number.getMax());
            } else {
                DecimalSetting decimal = (DecimalSetting) setting;
                progress = progress(decimal.getValue(), decimal.getMin(), decimal.getMax());
            }

            int fillX = sliderX + Math.round(sliderWidth * progress);
            Gui.drawRect(sliderX, trackY, fillX, trackY + 2, 0xFF000000 | accent);
            if (ClickGuiModule.areGuiEffectsEnabled()) {
                Gui.drawRect(sliderX, trackY - 1, fillX, trackY, GuiTheme.withAlpha(ClickGuiModule.getLightAccentColor(), 70));
            }
            Gui.drawRect(fillX - 1, trackY - 2, fillX + 2, trackY + 4, 0xFF000000 | accent);
        }

        private void updateSliderValue(Setting setting, Module module, int mouseX) {
            int sliderX = x + 10;
            int sliderWidth = width - 20;
            float progress = sliderWidth <= 0 ? 0.0F : (mouseX - sliderX) / (float) sliderWidth;
            progress = Math.max(0.0F, Math.min(1.0F, progress));

            if (setting instanceof NumberSetting) {
                NumberSetting number = (NumberSetting) setting;
                int value = steppedInt(number.getMin() + (progress * (number.getMax() - number.getMin())), number.getStep());
                number.setValue(value);
                enforceNumberBounds(module);
                return;
            }

            if (setting instanceof DecimalSetting) {
                DecimalSetting decimal = (DecimalSetting) setting;
                double value = decimal.getMin() + (progress * (decimal.getMax() - decimal.getMin()));
                decimal.setValue(steppedDouble(value, decimal.getStep()));
                return;
            }

            if (setting instanceof IntRangeSetting) {
                IntRangeSetting range = (IntRangeSetting) setting;
                int value = Math.round(range.getMin() + (progress * (range.getMax() - range.getMin())));
                if (slidingRangeLow) {
                    range.setLow(value, true);
                } else {
                    range.setHigh(value, true);
                }
            }
        }

        private boolean shouldDragLowRangeHandle(Setting setting, int mouseX) {
            if (!(setting instanceof IntRangeSetting)) {
                return false;
            }

            IntRangeSetting range = (IntRangeSetting) setting;
            int sliderX = x + 10;
            int sliderWidth = width - 20;
            int lowX = sliderX + Math.round(sliderWidth * progress(range.getLow(), range.getMin(), range.getMax()));
            int highX = sliderX + Math.round(sliderWidth * progress(range.getHigh(), range.getMin(), range.getMax()));
            return Math.abs(mouseX - lowX) <= Math.abs(mouseX - highX);
        }

        private float progress(double value, double min, double max) {
            if (max <= min) {
                return 0.0F;
            }
            return (float) Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
        }

        private int steppedInt(float value, int step) {
            int safeStep = Math.max(1, step);
            return Math.round(value / safeStep) * safeStep;
        }

        private double steppedDouble(double value, double step) {
            double safeStep = step <= 0.0D ? 1.0D : step;
            return Math.round(value / safeStep) * safeStep;
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
                return;
            }

            if (mouseButton == 1 && module.canBeUnbound()) {
                module.setKeyCode(Keyboard.KEY_NONE);
                bindingModule = null;
            }
        }

        private boolean handleKeyTyped(int keyCode) {
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
            return name == null ? "UNKNOWN" : name.toUpperCase();
        }

        private void updateHoveredModule(Module module) {
            long now = System.currentTimeMillis();
            if (module != hoveredModule) {
                hoveredModule = module;
                hoveredSince = module == null ? 0L : now;
            }
        }

        private boolean shouldShowDescription(Module module) {
            return module != null
                && module.getDescription() != null
                && !module.getDescription().isEmpty()
                && System.currentTimeMillis() - hoveredSince >= DESCRIPTION_HOVER_DELAY_MS;
        }

        private void drawModuleDescription(Module module, int rowY, net.minecraft.client.gui.FontRenderer fontRenderer, int screenWidth) {
            String description = module.getDescription();
            int padding = 6;
            int tooltipWidth = scaledWidth(fontRenderer, description, TEXT_SCALE) + (padding * 2);
            int tooltipX = x + (width - tooltipWidth) / 2;
            int tooltipY = rowY - ROW_HEIGHT - 7;

            if (tooltipX < 4) {
                tooltipX = 4;
            }

            int maxX = Math.max(4, screenWidth - tooltipWidth - 4);
            if (tooltipX > maxX) {
                tooltipX = maxX;
            }

            if (tooltipY < 4) {
                tooltipY = rowY + 2;
            }

            Gui.drawRect(tooltipX + 2, tooltipY + 2, tooltipX + tooltipWidth + 2, tooltipY + ROW_HEIGHT + 2, 0x90000000);
            Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + ROW_HEIGHT, 0xF010121A);
            Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + 1, 0xFF000000 | ClickGuiModule.getAccentColor());
            drawScaledString(fontRenderer, description, tooltipX + padding, tooltipY + 4, 0xFFFFFFFF, true, TEXT_SCALE);
        }

        private boolean isHovered(int mouseX, int mouseY, int rectX, int rectY, int width, int height) {
            return mouseX >= rectX && mouseX <= rectX + width && mouseY >= rectY && mouseY <= rectY + height;
        }

        private void setWidth(int width) {
            this.width = width;
        }

        private void setPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }

        private boolean wasMovedByUser() {
            return movedByUser;
        }

        private void clampToScreen(int screenWidth, int screenHeight) {
            int maxX = Math.max(2, screenWidth - width - 2);
            int maxY = Math.max(2, screenHeight - getPanelHeight(screenHeight) - 2);
            x = Math.max(2, Math.min(maxX, x));
            y = Math.max(18, Math.min(maxY, y));
            clampScroll(screenHeight);
        }

        private void drawPanelShadow(int x, int y, int width, int height) {
            Gui.drawRect(x + 2, y + 2, x + width + 2, y + height + 2, 0x66000000);
            Gui.drawRect(x + 1, y + 1, x + width + 1, y + height + 1, 0x44000000);
        }

        private boolean isBodyHovered(int mouseX, int mouseY, int screenHeight) {
            return isHovered(mouseX, mouseY, x, y + HEADER_HEIGHT, width, getVisibleBodyHeight(screenHeight));
        }

        private boolean isRowVisible(int rowY, int bodyTop, int bodyBottom) {
            return rowY + ROW_HEIGHT > bodyTop && rowY < bodyBottom;
        }

        private void drawScrollbar(int bodyTop, int bodyHeight, int accent) {
            int bodyContentHeight = getBodyContentHeight();
            if (bodyContentHeight <= bodyHeight || bodyHeight <= 0) {
                return;
            }
            int trackX = x + width - 3;
            Gui.drawRect(trackX, bodyTop + 2, trackX + 1, bodyTop + bodyHeight - 2, 0x66000000);
            int thumbHeight = Math.max(10, (bodyHeight * bodyHeight) / Math.max(1, bodyContentHeight));
            int maxScroll = Math.max(1, bodyContentHeight - bodyHeight);
            int thumbTravel = Math.max(1, bodyHeight - thumbHeight - 4);
            int thumbY = bodyTop + 2 + Math.round((scrollOffset / (float) maxScroll) * thumbTravel);
            int color = ClickGuiModule.areGuiEffectsEnabled()
                ? GuiTheme.withAlpha(ClickGuiModule.getLightAccentColor(), 170)
                : (0xFF000000 | accent);
            Gui.drawRect(trackX, thumbY, trackX + 1, thumbY + thumbHeight, color);
        }

        private void enableScissor(int x, int y, int width, int height, int screenHeight) {
            ScaledResolution resolution = new ScaledResolution(Minecraft.getMinecraft());
            int scaleFactor = resolution.getScaleFactor();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(
                x * scaleFactor,
                (screenHeight - y - height) * scaleFactor,
                Math.max(0, width * scaleFactor),
                Math.max(0, height * scaleFactor)
            );
        }

        private String formatCategoryName(Category category) {
            if (category == Category.LAG_MODULES) {
                return "Lag Modules";
            }
            String name = category.name().toLowerCase();
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }

        private String fitText(net.minecraft.client.gui.FontRenderer fontRenderer, String text, int maxWidth) {
            return fitText(fontRenderer, text, maxWidth, 1.0F);
        }

        private String fitText(net.minecraft.client.gui.FontRenderer fontRenderer, String text, int maxWidth, float scale) {
            if (text == null || maxWidth <= 0) {
                return "";
            }

            int logicalMaxWidth = Math.max(1, Math.round(maxWidth / Math.max(0.1F, scale)));
            if (fontRenderer.getStringWidth(text) <= logicalMaxWidth) {
                return text;
            }

            String suffix = "...";
            int suffixWidth = fontRenderer.getStringWidth(suffix);
            if (suffixWidth >= logicalMaxWidth) {
                return "";
            }

            String fitted = text;
            while (fitted.length() > 0 && fontRenderer.getStringWidth(fitted) + suffixWidth > logicalMaxWidth) {
                fitted = fitted.substring(0, fitted.length() - 1);
            }

            return fitted + suffix;
        }

        private int scaledWidth(net.minecraft.client.gui.FontRenderer fontRenderer, String text, float scale) {
            return Math.round(fontRenderer.getStringWidth(text == null ? "" : text) * scale);
        }

        private void drawScaledString(net.minecraft.client.gui.FontRenderer fontRenderer, String text, int x, int y, int color, boolean shadow, float scale) {
            GL11.glPushMatrix();
            GL11.glScalef(scale, scale, 1.0F);
            float drawX = x / scale;
            float drawY = y / scale;
            if (shadow) {
                fontRenderer.drawStringWithShadow(text, drawX, drawY, color);
            } else {
                fontRenderer.drawString(text, (int) drawX, (int) drawY, color);
            }
            GL11.glPopMatrix();
        }
    }
}
