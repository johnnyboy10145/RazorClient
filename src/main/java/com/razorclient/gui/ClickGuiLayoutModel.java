package com.razorclient.gui;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.ModuleManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Retained panel layout; render frames only update it when the viewport changes. */
final class ClickGuiLayoutModel {
    static final int SIDE_MARGIN = 8;
    static final int TOP_MARGIN = 58;
    private static final int MIN_PANEL_WIDTH = 94;
    private static final int MAX_PANEL_WIDTH = 118;

    private final List<ClickGuiCategoryPanel> panels;
    private boolean initialized;
    private int screenWidth;
    private int screenHeight;

    ClickGuiLayoutModel(ModuleManager moduleManager) {
        List<ClickGuiCategoryPanel> created = new ArrayList<ClickGuiCategoryPanel>();
        for (Category category : Category.values()) {
            created.add(new ClickGuiCategoryPanel(
                category,
                SIDE_MARGIN,
                TOP_MARGIN,
                MAX_PANEL_WIDTH,
                moduleManager.getModules(category)
            ));
        }
        panels = Collections.unmodifiableList(created);
    }

    List<ClickGuiCategoryPanel> panels() {
        return panels;
    }

    void update(int width, int height) {
        if (initialized && width == screenWidth && height == screenHeight) {
            return;
        }

        int count = Math.max(1, panels.size());
        int gap = width < 640 ? 4 : 6;
        int available = Math.max(MIN_PANEL_WIDTH * count, width - (SIDE_MARGIN * 2) - (gap * (count - 1)));
        int panelWidth = ClickGuiRenderUtil.clamp(available / count, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        int totalWidth = (panelWidth * count) + (gap * (count - 1));
        int startX = Math.max(4, (width - totalWidth) / 2);

        for (int index = 0; index < panels.size(); index++) {
            ClickGuiCategoryPanel panel = panels.get(index);
            panel.setWidth(panelWidth);
            if (!panel.wasMovedByUser() || !initialized) {
                panel.setPosition(startX + (index * (panelWidth + gap)), TOP_MARGIN);
            }
            panel.clampToScreen(width, height);
        }

        initialized = true;
        screenWidth = width;
        screenHeight = height;
    }

    void cancelInteractions() {
        for (ClickGuiCategoryPanel panel : panels) {
            panel.cancelInteraction();
        }
    }

    void setFilter(String query) {
        for (ClickGuiCategoryPanel panel : panels) {
            panel.setFilter(query);
        }
    }

    void invalidateContent() {
        for (ClickGuiCategoryPanel panel : panels) {
            panel.invalidateContentHeight();
        }
    }
}
