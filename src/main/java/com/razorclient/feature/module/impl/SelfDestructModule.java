package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.ActionSetting;
import com.razorclient.runtime.SecureStringTable;
import org.lwjgl.input.Keyboard;

public final class SelfDestructModule extends Module {
    public SelfDestructModule() {
        super("Self Destruct", "Unloads \u00AE\uFE0FazorClient from the current Minecraft session.", Category.CLIENT, Keyboard.KEY_NONE);
        addSetting(new ActionSetting("Unload Client", new Runnable() {
            @Override
            public void run() {
                unload();
            }
        }, new ActionSetting.ValueProvider() {
            @Override
            public String get() {
                return "UNLOAD";
            }
        }));
    }

    @Override
    public void toggle() {
        unload();
    }

    @Override
    public boolean showsKeybindSetting() {
        return false;
    }

    private void unload() {
        try {
            Class<?> entrypoint = Class.forName(SecureStringTable.liveEntrypoint());
            entrypoint.getMethod("unload").invoke(null);
        } catch (Throwable ignored) {
        }
    }
}
