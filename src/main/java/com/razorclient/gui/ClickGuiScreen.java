package com.razorclient.gui;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.ModuleManager;
import com.razorclient.feature.module.impl.ClickGuiModule;
import java.util.List;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class ClickGuiScreen extends GuiScreen {
    private static final int CONTROL_TOP = 29;
    private static final int CONTROL_HEIGHT = 20;
    private static final int PROFILE_VISIBLE_ROWS = 6;
    private static final int PROFILE_ROW_HEIGHT = 17;

    private final ClickGuiLayoutModel layout;
    private final ClickGuiProfileAdapter profiles;
    private GuiTextField searchField;
    private boolean profileDropdownOpen;
    private int profileScroll;
    private String appliedSearchText;

    public ClickGuiScreen(ModuleManager moduleManager) {
        layout = new ClickGuiLayoutModel(moduleManager);
        profiles = new ClickGuiProfileAdapter(moduleManager);
    }

    @Override
    public void initGui() {
        super.initGui();
        profileDropdownOpen = false;
        profileScroll = 0;
        appliedSearchText = null;
        profiles.refresh();
        layout.cancelInteractions();
        int searchWidth = getSearchWidth();
        searchField = new GuiTextField(0, fontRendererObj, 28, CONTROL_TOP + 5, searchWidth - 44, 12);
        searchField.setMaxStringLength(48);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setTextColor(0xFFE9EEE9);
        GuiCursor.enterGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        layout.update(width, height);
        updatePanelFilters();
        drawRazorBackground();
        drawBrandHeader();
        drawControlBar(mouseX, mouseY);

        int scaleFactor = new ScaledResolution(mc).getScaleFactor();
        for (ClickGuiCategoryPanel panel : layout.panels()) {
            panel.draw(mouseX, mouseY, fontRendererObj, width, height, scaleFactor);
        }
        for (ClickGuiCategoryPanel panel : layout.panels()) {
            panel.drawDescriptionOverlay(fontRendererObj, width);
        }

        drawProfileDropdown(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GuiCursor.draw(mouseX, mouseY);
    }

    @Override
    public void onGuiClosed() {
        profileDropdownOpen = false;
        if (searchField != null) {
            searchField.setFocused(false);
        }
        layout.cancelInteractions();
        profiles.flushPendingSave();
        super.onGuiClosed();
        GuiCursor.exitGui();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (handleControlClick(mouseX, mouseY, mouseButton)) {
            return;
        }
        profileDropdownOpen = false;
        List<ClickGuiCategoryPanel> panels = layout.panels();
        for (int index = panels.size() - 1; index >= 0; index--) {
            if (panels.get(index).mouseClicked(mouseX, mouseY, mouseButton, height)) {
                return;
            }
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (searchField != null && searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                if (!searchField.getText().isEmpty()) {
                    searchField.setText("");
                    updatePanelFilters();
                } else {
                    searchField.setFocused(false);
                }
                return;
            }
            if (searchField.textboxKeyTyped(typedChar, keyCode)) {
                updatePanelFilters();
                return;
            }
        }

        if (keyCode == Keyboard.KEY_ESCAPE && searchField != null && !searchField.getText().isEmpty()) {
            searchField.setText("");
            updatePanelFilters();
            return;
        }
        for (ClickGuiCategoryPanel panel : layout.panels()) {
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
        for (ClickGuiCategoryPanel panel : layout.panels()) {
            panel.mouseReleased();
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        for (ClickGuiCategoryPanel panel : layout.panels()) {
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
        int mouseX = Mouse.getEventX() * width / Math.max(1, mc.displayWidth);
        int mouseY = height - Mouse.getEventY() * height / Math.max(1, mc.displayHeight) - 1;
        if (profileDropdownOpen && isInsideProfileDropdown(mouseX, mouseY)) {
            int maxScroll = Math.max(0, profiles.names().size() - PROFILE_VISIBLE_ROWS);
            profileScroll = ClickGuiRenderUtil.clamp(profileScroll + (wheel > 0 ? -1 : 1), 0, maxScroll);
            return;
        }

        int amount = wheel > 0 ? -12 : 12;
        List<ClickGuiCategoryPanel> panels = layout.panels();
        for (int index = panels.size() - 1; index >= 0; index--) {
            if (panels.get(index).scroll(mouseX, mouseY, amount, height)) {
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

    private void drawRazorBackground() {
        Gui.drawRect(0, 0, width, height, GuiTheme.withAlpha(GuiTheme.pageBackground(), 232));
        Gui.drawRect(0, 0, width, 24, GuiTheme.withAlpha(GuiTheme.surface(), 245));
        Gui.drawRect(0, 24, width, 25, GuiTheme.withAlpha(ClickGuiModule.getAccentColor(), 80));
        Gui.drawRect(0, height - 1, width, height, GuiTheme.withAlpha(ClickGuiModule.getDarkAccentColor(), 70));
    }

    private void drawBrandHeader() {
        int accent = ClickGuiModule.getAccentColor();
        int logoX = Math.max(10, (width - Math.min(width - 16, 720)) / 2);
        drawHexMark(logoX, 7, accent);
        fontRendererObj.drawString("RAZOR", logoX + 17, 8, 0xFF000000 | GuiTheme.text());
        fontRendererObj.drawString("CLIENT", logoX + 47, 8, 0xFF000000 | accent);
        fontRendererObj.drawString("MODULE CONTROL", logoX + 82, 8, 0xFF000000 | GuiTheme.mutedText());
    }

    private void drawControlBar(int mouseX, int mouseY) {
        int accent = ClickGuiModule.getAccentColor();
        int searchWidth = getSearchWidth();
        Gui.drawRect(10, CONTROL_TOP, 10 + searchWidth, CONTROL_TOP + CONTROL_HEIGHT,
            GuiTheme.withAlpha(GuiTheme.surface(), 242));
        Gui.drawRect(10, CONTROL_TOP, 10 + searchWidth, CONTROL_TOP + 1,
            GuiTheme.withAlpha(searchField != null && searchField.isFocused()
                ? ClickGuiModule.getLightAccentColor() : GuiTheme.border(), 220));
        fontRendererObj.drawString("?", 15, CONTROL_TOP + 6, 0xFF000000 | accent);
        if (searchField != null) {
            searchField.setTextColor(0xFF000000 | GuiTheme.text());
            if (!searchField.isFocused() && searchField.getText().isEmpty()) {
                fontRendererObj.drawString("Search modules", 27, CONTROL_TOP + 6, 0xFF000000 | GuiTheme.mutedText());
            }
            searchField.drawTextBox();
            if (!searchField.getText().isEmpty()) {
                fontRendererObj.drawString("x", 10 + searchWidth - 10, CONTROL_TOP + 6, 0xFF91A097);
            }
        }

        int profileX = getProfileX();
        drawToolbarButton(profileX, getProfileWidth(), getProfileLabel(), profileDropdownOpen,
            ClickGuiRenderUtil.isHovered(mouseX, mouseY, profileX, CONTROL_TOP, getProfileWidth(), CONTROL_HEIGHT));
        int buttonX = profileX + getProfileWidth() + 4;
        drawToolbarButton(buttonX, 34, "NEW", false,
            ClickGuiRenderUtil.isHovered(mouseX, mouseY, buttonX, CONTROL_TOP, 34, CONTROL_HEIGHT));
        buttonX += 38;
        drawToolbarButton(buttonX, 34, "DEL", false,
            ClickGuiRenderUtil.isHovered(mouseX, mouseY, buttonX, CONTROL_TOP, 34, CONTROL_HEIGHT));
        buttonX += 38;
        drawToolbarButton(buttonX, 42, "FOLDER", false,
            ClickGuiRenderUtil.isHovered(mouseX, mouseY, buttonX, CONTROL_TOP, 42, CONTROL_HEIGHT));
    }

    private void drawToolbarButton(int x, int buttonWidth, String label, boolean selected, boolean hovered) {
        int fill = 0xFF000000 | (selected || hovered ? GuiTheme.hoverSurface() : GuiTheme.surface());
        Gui.drawRect(x, CONTROL_TOP, x + buttonWidth, CONTROL_TOP + CONTROL_HEIGHT, fill);
        Gui.drawRect(x, CONTROL_TOP + CONTROL_HEIGHT - 1, x + buttonWidth, CONTROL_TOP + CONTROL_HEIGHT,
            GuiTheme.withAlpha(selected || hovered ? ClickGuiModule.getAccentColor() : GuiTheme.border(), 210));
        drawCenteredString(fontRendererObj, fitToolbarText(label, buttonWidth - 8), x + buttonWidth / 2, CONTROL_TOP + 6,
            selected ? 0xFF000000 | ClickGuiModule.getLightAccentColor() : 0xFF000000 | GuiTheme.text());
    }

    private void drawProfileDropdown(int mouseX, int mouseY) {
        if (!profileDropdownOpen) {
            return;
        }
        List<String> names = profiles.names();
        profileScroll = ClickGuiRenderUtil.clamp(profileScroll, 0, Math.max(0, names.size() - PROFILE_VISIBLE_ROWS));
        int x = getProfileX();
        int dropdownWidth = getProfileWidth();
        int y = CONTROL_TOP + CONTROL_HEIGHT + 2;
        int count = Math.min(PROFILE_VISIBLE_ROWS, Math.max(0, names.size() - profileScroll));
        Gui.drawRect(x - 1, y - 1, x + dropdownWidth + 1, y + (count * PROFILE_ROW_HEIGHT) + 1, 0xF6000000);
        for (int index = 0; index < count; index++) {
            String name = names.get(profileScroll + index);
            int rowY = y + index * PROFILE_ROW_HEIGHT;
            boolean hovered = ClickGuiRenderUtil.isHovered(mouseX, mouseY, x, rowY, dropdownWidth, PROFILE_ROW_HEIGHT);
            boolean active = name.equalsIgnoreCase(profiles.currentName());
            Gui.drawRect(x, rowY, x + dropdownWidth, rowY + PROFILE_ROW_HEIGHT,
                0xFF000000 | (hovered ? GuiTheme.hoverSurface() : GuiTheme.surface()));
            if (active) {
                Gui.drawRect(x, rowY, x + 2, rowY + PROFILE_ROW_HEIGHT, 0xFF000000 | ClickGuiModule.getAccentColor());
            }
            fontRendererObj.drawString(fitToolbarText(name, dropdownWidth - 12), x + 7, rowY + 5,
                active ? 0xFF000000 | ClickGuiModule.getLightAccentColor() : 0xFF000000 | GuiTheme.text());
        }
    }

    private boolean handleControlClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        int searchWidth = getSearchWidth();
        if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, 10, CONTROL_TOP, searchWidth, CONTROL_HEIGHT)) {
            if (mouseX >= 10 + searchWidth - 16 && searchField != null && !searchField.getText().isEmpty()) {
                searchField.setText("");
                updatePanelFilters();
            } else if (searchField != null) {
                searchField.setFocused(true);
            }
            profileDropdownOpen = false;
            return true;
        }
        if (searchField != null) {
            searchField.setFocused(false);
        }

        int profileX = getProfileX();
        if (profileDropdownOpen && isInsideProfileDropdown(mouseX, mouseY)) {
            List<String> names = profiles.names();
            int selected = profileScroll + ((mouseY - (CONTROL_TOP + CONTROL_HEIGHT + 2)) / PROFILE_ROW_HEIGHT);
            if (selected >= 0 && selected < names.size()) {
                profiles.load(names.get(selected));
                layout.invalidateContent();
            }
            profileDropdownOpen = false;
            return true;
        }
        if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, profileX, CONTROL_TOP, getProfileWidth(), CONTROL_HEIGHT)) {
            profileDropdownOpen = !profileDropdownOpen;
            if (profileDropdownOpen) {
                profiles.refresh();
            }
            return true;
        }
        int buttonX = profileX + getProfileWidth() + 4;
        if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, buttonX, CONTROL_TOP, 34, CONTROL_HEIGHT)) {
            profiles.create();
            layout.invalidateContent();
            return true;
        }
        buttonX += 38;
        if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, buttonX, CONTROL_TOP, 34, CONTROL_HEIGHT)) {
            profiles.deleteCurrent();
            layout.invalidateContent();
            return true;
        }
        buttonX += 38;
        if (ClickGuiRenderUtil.isHovered(mouseX, mouseY, buttonX, CONTROL_TOP, 42, CONTROL_HEIGHT)) {
            profiles.openFolder();
            return true;
        }
        return false;
    }

    private void updatePanelFilters() {
        String query = searchField == null ? "" : searchField.getText();
        if (query.equals(appliedSearchText)) {
            return;
        }
        appliedSearchText = query;
        layout.setFilter(query);
    }

    private int getSearchWidth() {
        return ClickGuiRenderUtil.clamp(width / 3, 100, 210);
    }

    private int getProfileWidth() {
        return width < 520 ? 66 : 100;
    }

    private int getProfileX() {
        return Math.max(10 + getSearchWidth() + 6, width - getProfileWidth() - 126);
    }

    private String getProfileLabel() {
        String name = profiles.currentName();
        return name == null ? "PROFILE" : name;
    }

    private boolean isInsideProfileDropdown(int mouseX, int mouseY) {
        int count = Math.min(PROFILE_VISIBLE_ROWS, Math.max(0, profiles.names().size() - profileScroll));
        return ClickGuiRenderUtil.isHovered(mouseX, mouseY, getProfileX(), CONTROL_TOP + CONTROL_HEIGHT + 2,
            getProfileWidth(), count * PROFILE_ROW_HEIGHT);
    }

    private String fitToolbarText(String text, int maxWidth) {
        return ClickGuiRenderUtil.fitText(fontRendererObj, text, maxWidth, 1.0F);
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
}
