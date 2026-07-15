package com.razorclient.inject;

import com.razorclient.RazorClient;
import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.event.PrePlayerInputEvent;
import com.razorclient.event.PrePlayerInteractEvent;
import com.razorclient.event.RunTickStartEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class ClientHooks {
    private static volatile RazorClient client;
    private static boolean tickDispatchActive;

    static void start() {
        ClientRotationHelper.get().start();
        client = RazorClient.bootstrap();
    }

    static void stop() {
        ClientRotationHelper.get().stop();
        tickDispatchActive = false;
        client = null;
    }

    public static void runTickHead() {
        if (client == null) client = RazorClient.bootstrap();
        RazorClient value = client;
        if (value == null) {
            tickDispatchActive = false;
            return;
        }
        value.getModuleManager().pollLifecycleState();
        if (!value.getModuleManager().beginRealTick()) {
            tickDispatchActive = false;
            return;
        }
        tickDispatchActive = true;
        ClientRotationHelper.get().onRunTickStart();
        MinecraftForge.EVENT_BUS.post(new RunTickStartEvent());
        value.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) value.getModuleManager().onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.START, mc.thePlayer));
        ClientRotationHelper.get().updateServerRotations();
    }

    public static void runTickTail() {
        if (!tickDispatchActive) return;
        tickDispatchActive = false;
        RazorClient value = client;
        if (value != null) {
            MinecraftForge.EVENT_BUS.post(new PrePlayerInteractEvent());
            value.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) value.getModuleManager().onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, mc.thePlayer));
        }
    }

    public static void keyEvent() {
        if (!Keyboard.getEventKeyState()) return;
        int key = Keyboard.getEventKey();
        if (key == Keyboard.KEY_NONE) return;
        RazorClient value = client;
        if (value != null) value.onKey(key);
    }

    public static void walkingHead(Entity player) {
        ClientRotationHelper helper = ClientRotationHelper.get();
        helper.updateServerRotations();
        helper.onWalkingUpdatePre(player);
    }

    public static void walkingTail(Entity player) {
        ClientRotationHelper.get().onWalkingUpdatePost(player);
    }

    public static void movementInput(Object input) {
        // Field mutation is isolated in the adapter to keep module code unchanged.
        LunarAccess.rewriteMovementInput(input, new PrePlayerInputEvent(0, 0, false, false));
    }

    public static void mouseEvent() {
        RazorClient value = client;
        if (value == null) return;
        MouseEvent event = new MouseEvent();
        event.button = Mouse.getEventButton();
        event.buttonstate = Mouse.getEventButtonState();
        event.dwheel = Mouse.getEventDWheel();
        event.dx = Mouse.getEventDX();
        event.dy = Mouse.getEventDY();
        value.getModuleManager().onMouseEvent(event);
    }

    public static void renderFrame(float partialTicks) {
        RazorClient value = client;
        if (value != null) {
            if (value.getModuleManager().getFrameContext() == null) value.getModuleManager().beginFrame(partialTicks);
            value.getModuleManager().onRenderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.END, partialTicks));
        }
    }

    public static void renderWorld(float partialTicks) {
        RazorClient value = client;
        if (value != null) {
            value.getModuleManager().beginFrame(partialTicks);
            value.getModuleManager().onRenderWorld(new RenderWorldLastEvent(partialTicks));
        }
    }

    public static void renderOverlay(float partialTicks) {
        RazorClient value = client;
        Minecraft mc = Minecraft.getMinecraft();
        if (value != null) value.getModuleManager().onRenderOverlay(new RenderGameOverlayEvent.Text(partialTicks, new ScaledResolution(mc)));
    }

    public static boolean outbound(Packet<?> packet) {
        RazorClient value = client;
        return value != null && value.getPacketDelayManager().interceptOutbound(packet, null);
    }

    public static boolean inbound(NetworkManager manager, Packet<?> packet) {
        RazorClient value = client;
        if (value == null) return false;
        try {
            java.lang.reflect.Field field = NetworkManager.class.getDeclaredField("packetListener");
            field.setAccessible(true);
            INetHandler listener = (INetHandler) field.get(manager);
            return value.getPacketDelayManager().interceptInbound(packet, listener);
        } catch (ReflectiveOperationException failure) {
            AgentLog.error("Network listener lookup failed", failure);
            return false;
        }
    }

    public static boolean isClientThread() {
        return Minecraft.getMinecraft().isCallingFromMinecraftThread();
    }

    private ClientHooks() {}
}
