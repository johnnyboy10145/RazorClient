package com.razorclient.gui;

import com.razorclient.feature.module.impl.ClickGuiModule;
import java.nio.IntBuffer;
import net.minecraft.client.gui.Gui;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public final class GuiCursor {
    private static boolean nativeHidden;
    private static Cursor transparentCursor;

    private GuiCursor() {
    }

    public static void enterGui() {
        setNativeCursorHidden(ClickGuiModule.isCustomCursorEnabled());
    }

    public static void exitGui() {
        setNativeCursorHidden(false);
    }

    public static void shutdown() {
        setNativeCursorHidden(false);
        Cursor cursor = transparentCursor;
        transparentCursor = null;
        if (cursor != null) {
            try {
                cursor.destroy();
            } catch (Throwable ignored) {
                // Native display teardown may already be in progress.
            }
        }
    }

    public static void draw(int mouseX, int mouseY) {
        if (!ClickGuiModule.isCustomCursorEnabled()) {
            setNativeCursorHidden(false);
            return;
        }

        setNativeCursorHidden(true);
        int scale = Math.max(40, ClickGuiModule.getCursorScale());
        float renderScale = Math.max(0.45F, scale / 100.0F);
        GL11.glPushMatrix();
        GL11.glTranslatef(mouseX, mouseY, 0.0F);
        GL11.glScalef(renderScale, renderScale, 1.0F);
        drawDarkFlameCursor();
        GL11.glPopMatrix();
    }

    private static void drawDarkFlameCursor() {
        int accent = GuiTheme.accent();
        int light = GuiTheme.lightAccent();
        int dark = GuiTheme.darkAccent();
        int shadow = 0xB0000000;

        drawBlock(6, 1, 2, 2, GuiTheme.withAlpha(light, 70));
        drawBlock(4, 4, 3, 3, GuiTheme.withAlpha(accent, 58));
        drawBlock(9, 6, 2, 3, GuiTheme.withAlpha(dark, 68));
        drawBlock(4, 9, 2, 2, GuiTheme.withAlpha(accent, 42));

        drawBlock(1, 1, 2, 2, shadow);
        drawBlock(2, 2, 3, 3, shadow);
        drawBlock(3, 4, 4, 3, shadow);
        drawBlock(4, 7, 4, 3, shadow);
        drawBlock(6, 10, 3, 3, shadow);
        drawBlock(8, 13, 2, 2, shadow);

        drawBlock(0, 0, 2, 2, 0xFFF4F1FF);
        drawBlock(2, 2, 2, 2, GuiTheme.withAlpha(light, 245));
        drawBlock(3, 4, 3, 2, GuiTheme.withAlpha(accent, 235));
        drawBlock(4, 6, 3, 2, GuiTheme.withAlpha(accent, 225));
        drawBlock(5, 8, 3, 2, GuiTheme.withAlpha(dark, 235));
        drawBlock(6, 10, 2, 2, 0xFF10121A);

        drawBlock(9, 2, 1, 2, GuiTheme.withAlpha(light, 180));
        drawBlock(10, 4, 1, 2, GuiTheme.withAlpha(accent, 145));
        drawBlock(7, 9, 1, 2, GuiTheme.withAlpha(light, 120));
    }

    private static void drawBlock(int x, int y, int width, int height, int color) {
        Gui.drawRect(x, y, x + Math.max(1, width), y + Math.max(1, height), color);
    }

    private static void setNativeCursorHidden(boolean hidden) {
        if (hidden == nativeHidden || !Mouse.isCreated()) {
            return;
        }

        try {
            if (hidden) {
                if (transparentCursor == null) {
                    IntBuffer buffer = BufferUtils.createIntBuffer(1);
                    buffer.put(0);
                    buffer.flip();
                    transparentCursor = new Cursor(1, 1, 0, 0, 1, buffer, null);
                }
                Mouse.setNativeCursor(transparentCursor);
                nativeHidden = true;
            } else {
                Mouse.setNativeCursor(null);
                nativeHidden = false;
            }
        } catch (Throwable ignored) {
            nativeHidden = false;
        }
    }
}
