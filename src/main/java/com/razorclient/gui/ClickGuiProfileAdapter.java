package com.razorclient.gui;

import com.razorclient.config.ConfigManager;
import com.razorclient.feature.module.ModuleManager;
import java.util.Collections;
import java.util.List;

/** Keeps profile persistence and directory access outside the render loop. */
final class ClickGuiProfileAdapter {
    private final ConfigManager configManager;
    private final ModuleManager moduleManager;
    private List<String> cachedNames = Collections.emptyList();

    ClickGuiProfileAdapter(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
        this.configManager = moduleManager.getConfigManager();
    }

    void refresh() {
        cachedNames = configManager.listConfigs();
    }

    List<String> names() {
        return cachedNames;
    }

    String currentName() {
        return configManager.getCurrentConfigName();
    }

    void load(String name) {
        configManager.load(name);
        refresh();
    }

    void create() {
        configManager.createNextConfig();
        moduleManager.refreshConfigModule();
        refresh();
    }

    void deleteCurrent() {
        configManager.deleteCurrentConfig();
        moduleManager.refreshConfigModule();
        refresh();
    }

    void openFolder() {
        configManager.openFolder();
    }

    void flushPendingSave() {
        ConfigManager.flushPendingSaveNow();
    }
}
