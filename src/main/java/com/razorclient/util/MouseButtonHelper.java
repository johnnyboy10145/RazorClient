package com.razorclient.util;

import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

public final class MouseButtonHelper {
    private static final boolean[] SYNTHETIC_HELD = new boolean[8];
    private static final ThreadLocal<Integer> SYNTHETIC_DEPTH = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return Integer.valueOf(0);
        }
    };

    private MouseButtonHelper() {
    }

    public static boolean isDispatchingSyntheticEvent() {
        return SYNTHETIC_DEPTH.get().intValue() > 0;
    }

    public static void setButton(int mouseButton, boolean held) {
        if (mouseButton < 0 || mouseButton >= SYNTHETIC_HELD.length) return;
        if (SYNTHETIC_HELD[mouseButton] == held) return;
        MouseEvent event = new MouseEvent();
        SYNTHETIC_DEPTH.set(Integer.valueOf(SYNTHETIC_DEPTH.get().intValue() + 1));
        try {
            ObfuscationReflectionHelper.setPrivateValue(MouseEvent.class, event, Integer.valueOf(mouseButton), "button");
            ObfuscationReflectionHelper.setPrivateValue(MouseEvent.class, event, Boolean.valueOf(held), "buttonstate");
            MinecraftForge.EVENT_BUS.post(event);
            SYNTHETIC_HELD[mouseButton] = held;
        } finally {
            int depth = Math.max(0, SYNTHETIC_DEPTH.get().intValue() - 1);
            if (depth == 0) {
                SYNTHETIC_DEPTH.remove();
            } else {
                SYNTHETIC_DEPTH.set(Integer.valueOf(depth));
            }
        }
    }

    public static void releaseAllSynthetic() {
        for (int button = 0; button < SYNTHETIC_HELD.length; button++) {
            if (!SYNTHETIC_HELD[button]) continue;
            try {
                setButton(button, false);
            } catch (RuntimeException ignored) {
                SYNTHETIC_HELD[button] = false;
            }
        }
    }
}
