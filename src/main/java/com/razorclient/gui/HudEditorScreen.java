package com.razorclient.gui;

import com.razorclient.config.ConfigManager;
import com.razorclient.feature.module.impl.ClickGuiModule;
import com.razorclient.feature.module.impl.HudModule;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

public final class HudEditorScreen extends GuiScreen {
    private final HudModule hudModule;

    public HudEditorScreen(HudModule hudModule) {
        this.hudModule = hudModule;
    }

    @Override
    public void initGui() {
        super.initGui();
        GuiCursor.enterGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawHeader();

        if (hudModule == null) {
            drawCenteredString(this.fontRendererObj, "HUD module missing", this.width / 2, this.height / 2, 0xFFFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
            GuiCursor.draw(mouseX, mouseY);
            return;
        }

        ScaledResolution resolution = new ScaledResolution(this.mc);
        hudModule.updateEditorDrag(mouseX, mouseY, resolution);
        hudModule.renderEditorOverlays(resolution, mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GuiCursor.draw(mouseX, mouseY);
    }

    @Override
    public void onGuiClosed() {
        if (hudModule != null) {
            hudModule.editorMouseReleased();
        }
        ConfigManager.flushPendingSaveNow();
        super.onGuiClosed();
        GuiCursor.exitGui();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (hudModule != null) {
            hudModule.editorMouseClicked(mouseX, mouseY, mouseButton, new ScaledResolution(this.mc));
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (hudModule != null) {
            hudModule.editorMouseReleased();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawHeader() {
        int accent = ClickGuiModule.getAccentColor();
        int panelWidth = Math.min(260, this.width - 16);
        int left = (this.width - panelWidth) / 2;
        int top = 8;
        if (ClickGuiModule.areGuiEffectsEnabled()) {
            GuiEffects.drawGlowPanel(left, top, panelWidth, 36, 0.55F, ClickGuiModule.getGlowIntensity());
            GuiEffects.drawGradientFill(left, top, panelWidth, 36, 20);
            if (ClickGuiModule.isSweepAnimationEnabled()) {
                GuiEffects.drawSweep(left, top, panelWidth, 36, 0.35F, ClickGuiModule.getGlowIntensity());
            }
        } else {
            Gui.drawRect(left + 2, top + 2, left + panelWidth + 2, top + 38, 0x66000000);
            Gui.drawRect(left, top, left + panelWidth, top + 36, 0xEE080A10);
        }
        Gui.drawRect(left, top, left + 2, top + 36, 0xFF000000 | accent);
        Gui.drawRect(left, top, left + panelWidth, top + 1, 0x35FFFFFF);
        drawCenteredString(this.fontRendererObj, "RazorClient Overlays", this.width / 2, top + 7, 0xFFFFFFFF);
        drawCenteredString(this.fontRendererObj, "Drag boxes. Click P to pin. ESC closes.", this.width / 2, top + 20, 0xFFB8BECF);
    }
}
