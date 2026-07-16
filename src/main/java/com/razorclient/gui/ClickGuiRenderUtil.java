package com.razorclient.gui;

import com.razorclient.feature.module.Category;
import net.minecraft.client.gui.FontRenderer;
import org.lwjgl.opengl.GL11;

final class ClickGuiRenderUtil {
    private ClickGuiRenderUtil() {
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static boolean isHovered(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    static String formatCategoryName(Category category) {
        if (category == Category.LAG_MODULES) {
            return "Lag Modules";
        }
        String name = category.name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static String fitText(FontRenderer renderer, String text, int maxWidth, float scale) {
        if (text == null || maxWidth <= 0) {
            return "";
        }

        int logicalMaxWidth = Math.max(1, Math.round(maxWidth / Math.max(0.1F, scale)));
        if (renderer.getStringWidth(text) <= logicalMaxWidth) {
            return text;
        }

        final String suffix = "...";
        int suffixWidth = renderer.getStringWidth(suffix);
        if (suffixWidth >= logicalMaxWidth) {
            return "";
        }

        int end = text.length();
        while (end > 0 && renderer.getStringWidth(text.substring(0, end)) + suffixWidth > logicalMaxWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    static int scaledWidth(FontRenderer renderer, String text, float scale) {
        return Math.round(renderer.getStringWidth(text == null ? "" : text) * scale);
    }

    static void drawScaledString(FontRenderer renderer, String text, int x, int y, int color, boolean shadow, float scale) {
        GL11.glPushMatrix();
        try {
            GL11.glScalef(scale, scale, 1.0F);
            float drawX = x / scale;
            float drawY = y / scale;
            if (shadow) {
                renderer.drawStringWithShadow(text, drawX, drawY, color);
            } else {
                renderer.drawString(text, (int) drawX, (int) drawY, color);
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    static void enableScissor(int x, int y, int width, int height, int screenHeight, int scaleFactor) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            x * scaleFactor,
            (screenHeight - y - height) * scaleFactor,
            Math.max(0, width * scaleFactor),
            Math.max(0, height * scaleFactor)
        );
    }
}
