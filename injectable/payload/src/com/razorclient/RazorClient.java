package com.razorclient;

import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.ModuleManager;
import com.razorclient.feature.module.impl.HudModule;
import com.razorclient.gui.ClickGuiScreen;
import com.razorclient.gui.HudEditorScreen;
import com.razorclient.network.KnockbackDelayBuffer;
import com.razorclient.network.PacketDelayManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class RazorClient {
    public static final String MOD_ID = "razorclient";
    public static final String NAME = "\u00AE\uFE0FazorClient";
    public static final String VERSION = "2.0.0-injectable";
    private static volatile RazorClient instance;

    private final ModuleManager moduleManager = new ModuleManager();
    private final PacketDelayManager packetDelayManager = new PacketDelayManager(moduleManager);
    private final KnockbackDelayBuffer knockbackDelayBuffer = new KnockbackDelayBuffer();
    private final ClickGuiScreen clickGuiScreen = new ClickGuiScreen(moduleManager);

    public static synchronized RazorClient bootstrap() {
        if (instance == null) {
            instance = new RazorClient();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> instance.moduleManager.getConfigManager().saveCurrent(), "RazorClient-ConfigSave"));
        }
        return instance;
    }

    public static RazorClient getInstance() { return instance; }
    public ModuleManager getModuleManager() { return moduleManager; }
    public PacketDelayManager getPacketDelayManager() { return packetDelayManager; }
    public KnockbackDelayBuffer getKnockbackDelayBuffer() { return knockbackDelayBuffer; }

    public static synchronized void shutdownForUnload() {
        RazorClient client = instance;
        if (client == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && (mc.currentScreen == client.clickGuiScreen || mc.currentScreen instanceof HudEditorScreen)) {
            mc.displayGuiScreen(null);
        }
        client.packetDelayManager.flushAll();
        client.moduleManager.shutdownForUnload();
        client.moduleManager.getConfigManager().saveCurrent();
        instance = null;
    }

    public void onClientTick(TickEvent.ClientTickEvent event) {
        moduleManager.onClientTick(event);
        if (event.phase == TickEvent.Phase.END) {
            moduleManager.onClientTick();
            knockbackDelayBuffer.onClientTick();
            packetDelayManager.onClientTick();
        }
    }

    public void onKey(int keyCode) {
        if (keyCode == 0) return;
        for (Module module : moduleManager.getModules()) if (module.getKeyCode() == keyCode) module.toggle();
    }

    public void toggleClickGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == clickGuiScreen) mc.displayGuiScreen(null);
        else mc.displayGuiScreen(clickGuiScreen);
    }

    public void openHudEditor() {
        HudModule hud = HudModule.getInstance();
        if (hud != null) Minecraft.getMinecraft().displayGuiScreen(new HudEditorScreen(hud));
    }
}
