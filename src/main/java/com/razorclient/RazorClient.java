package com.razorclient;

import com.razorclient.feature.module.ModuleManager;
import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.feature.module.impl.HudModule;
import com.razorclient.gui.ClickGuiScreen;
import com.razorclient.gui.HudEditorScreen;
import com.razorclient.input.KeybindHandler;
import com.razorclient.network.PacketDelayManager;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid = RazorClient.MOD_ID, name = RazorClient.NAME, version = RazorClient.VERSION, clientSideOnly = true)
public final class RazorClient {
    public static final String MOD_ID = "razorclient";
    public static final String NAME = "\u00AE\uFE0FazorClient";
    public static final String VERSION = "1.1.1";

    private static RazorClient instance;
    private final ModuleManager moduleManager = new ModuleManager();
    private final PacketDelayManager packetDelayManager = new PacketDelayManager(moduleManager);
    private final ClickGuiScreen clickGuiScreen = new ClickGuiScreen(moduleManager);
    private boolean tickDispatchActive;

    public static RazorClient getInstance() {
        return instance;
    }

    public static synchronized void shutdownForUnload() {
        RazorClient client = instance;
        if (client == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null && (minecraft.currentScreen == client.clickGuiScreen || minecraft.currentScreen instanceof HudEditorScreen)) {
            minecraft.displayGuiScreen(null);
        }
        client.moduleManager.getConfigManager().saveCurrent();
        client.packetDelayManager.closeForUnload();
        client.moduleManager.shutdownForUnload();
        ClientRotationHelper.get().stop();
        instance = null;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public PacketDelayManager getPacketDelayManager() {
        return packetDelayManager;
    }


    @EventHandler
    public void onPreInit(FMLPreInitializationEvent event) {
        instance = this;
    }

    @EventHandler
    public void onInit(FMLInitializationEvent event) {
        ClientRotationHelper.get().start();
        KeybindHandler.register(moduleManager);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                moduleManager.getConfigManager().saveCurrent();
            }
        }, "RazorClient-ConfigSave"));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            moduleManager.pollLifecycleState();
            tickDispatchActive = moduleManager.beginRealTick();
            if (tickDispatchActive) moduleManager.onClientTick(event);
            return;
        }
        if (!tickDispatchActive) return;
        tickDispatchActive = false;
        moduleManager.onClientTick(event);
        moduleManager.onClientTick();
        packetDelayManager.onClientTick();
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        moduleManager.onPlayerTick(event);
    }

    public void toggleClickGui() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen == clickGuiScreen) {
            minecraft.displayGuiScreen(null);
            return;
        }

        if (minecraft.currentScreen == null) {
            minecraft.displayGuiScreen(clickGuiScreen);
        }
    }

    public void openHudEditor() {
        HudModule hudModule = HudModule.getInstance();
        if (hudModule == null) {
            return;
        }

        Minecraft.getMinecraft().displayGuiScreen(new HudEditorScreen(hudModule));
    }

    @SubscribeEvent
    public void onMouseInput(MouseEvent event) {
        moduleManager.onMouseEvent(event);
    }

    @SubscribeEvent
    public void onPlayerJump(LivingEvent.LivingJumpEvent event) {
        moduleManager.onPlayerJump(event);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        moduleManager.onRenderTick(event);
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        moduleManager.beginFrame(event.partialTicks);
        moduleManager.onRenderWorld(event);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        moduleManager.onRenderOverlay(event);
    }
}
