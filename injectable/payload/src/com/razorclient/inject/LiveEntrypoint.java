package com.razorclient.inject;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Module;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraftforge.client.event.MouseEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public final class LiveEntrypoint {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean();
    private static final String INBOUND_HANDLER_NAME = "razorclient_live_inbound";
    private static final String OUTBOUND_HANDLER_NAME = "razorclient_live_outbound";
    private static volatile boolean running;
    private static volatile String injectionId;
    private static volatile NetworkManager installedManager;
    private static volatile NetworkManager pendingManager;
    private static final Set<Integer> downKeys = new HashSet<Integer>();
    private static final boolean[] downMouseButtons = new boolean[8];
    private static boolean supportLogged;
    private static boolean firstPulseLogged;
    private static boolean renderBridgeLogged;
    private static boolean firstWorldRenderLogged;
    private static final AtomicBoolean RENDERING = new AtomicBoolean();

    private static native boolean installNativeRenderBridge();
    private static native void uninstallNativeRenderBridge();

    public static void start() {
        String activeEpoch = System.getProperty("razorclient.live.epoch");
        if ("true".equals(System.getProperty("razorclient.live.injected")) && activeEpoch != null && !activeEpoch.isEmpty()) {
            AgentLog.info("Already injected; active epoch=" + activeEpoch);
            InjectionStatus.write("ALREADY_INJECTED", "active epoch=" + activeEpoch);
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            AgentLog.info("Already injected");
            InjectionStatus.write("ALREADY_INJECTED", "entrypoint already started");
            return;
        }
        try {
            unloadParentEntrypoint();
            injectionId = UUID.randomUUID().toString();
            running = true;
            ClientHooks.start();
            if (!installNativeRenderBridge()) {
                throw new IllegalStateException("Native render bridge installation failed");
            }
            System.setProperty("razorclient.live.injected", "true");
            System.setProperty("razorclient.live.epoch", injectionId);
            AgentLog.info("LiveEntrypoint started with loader " + LiveEntrypoint.class.getClassLoader());
            InjectionStatus.write("JAVA_STARTED", "epoch=" + injectionId);
            Thread loop = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running && isCurrentEpoch()) {
                        scheduleClientPulse();
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }, "RazorClient-LiveLoop");
            loop.setDaemon(true);
            loop.start();
        } catch (Throwable failure) {
            running = false;
            STARTED.set(false);
            System.clearProperty("razorclient.live.injected");
            InjectionStatus.write("FAILED", "LiveEntrypoint startup failed: " + failure);
            AgentLog.error("LiveEntrypoint startup failed", failure);
            throw failure;
        }
    }

    public static void unload() {
        running = false;
        try {
            uninstallNativeRenderBridge();
        } catch (Throwable failure) {
            AgentLog.error("Native render bridge removal failed", failure);
        }
        removePacketHandler();
        if (isCurrentEpoch()) {
            System.clearProperty("razorclient.live.injected");
            System.clearProperty("razorclient.live.epoch");
        }
        STARTED.set(false);
        supportLogged = false;
        firstPulseLogged = false;
        renderBridgeLogged = false;
        firstWorldRenderLogged = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    RazorClient client = RazorClient.getInstance();
                    if (client != null) client.getPacketDelayManager().flushAll();
                    RazorClient.shutdownForUnload();
                    ClientHooks.stop();
                    downKeys.clear();
                    for (int i = 0; i < downMouseButtons.length; i++) {
                        downMouseButtons[i] = false;
                    }
                    SCHEDULED.set(false);
                    AgentLog.info("LiveEntrypoint unloaded");
                    InjectionStatus.write("UNLOADED", "client shutdown completed");
                }
            });
        } else {
            InjectionStatus.write("UNLOADED", "Minecraft instance unavailable");
        }
    }

    private static void scheduleClientPulse() {
        if (!isCurrentEpoch()) {
            running = false;
            STARTED.set(false);
            return;
        }
        if (!SCHEDULED.compareAndSet(false, true)) return;
        try {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        pulseOnClientThread();
                    } catch (Throwable failure) {
                        AgentLog.error("Live client pulse failed", failure);
                    } finally {
                        SCHEDULED.set(false);
                    }
                }
            });
        } catch (Throwable failure) {
            SCHEDULED.set(false);
            AgentLog.error("Unable to schedule client pulse", failure);
        }
    }

    private static void pulseOnClientThread() {
        if (!isCurrentEpoch()) {
            running = false;
            STARTED.set(false);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        RazorClient client = RazorClient.bootstrap();
        if (!firstPulseLogged) {
            firstPulseLogged = true;
            InjectionStatus.write("FIRST_CLIENT_PULSE", "client thread pulse reached");
        }
        logLiveSupport(client);
        installPacketHandler(mc);
        pollKeybinds(client);
        pollMouseButtons(client);
        ClientHooks.runTickHead();
        if (mc.theWorld == null || mc.thePlayer == null) {
            // Keep lifecycle and packet-flush hooks active during world transitions.
            ClientHooks.runTickTail();
            return;
        }
        rewriteMovementInput(mc.thePlayer);
        ClientHooks.runTickTail();
    }

    public static void renderNativeFrame() {
        if (!running || !isCurrentEpoch() || !RENDERING.compareAndSet(false, true)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.currentScreen != null) {
            RENDERING.set(false);
            return;
        }
        try {
            if (!renderBridgeLogged) {
                renderBridgeLogged = true;
                AgentLog.info("Live render bridge active");
                InjectionStatus.write("RENDER_BRIDGE_ACTIVE", "OpenGL frame callback active");
            }
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            try {
                mc.entityRenderer.setupCameraTransform(1.0F, 0);
                ClientHooks.renderWorld(1.0F);
                mc.entityRenderer.setupOverlayRendering();
                ClientHooks.renderOverlay(1.0F);
                ClientHooks.renderFrame(1.0F);
            } finally {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopAttrib();
            }
            if (!firstWorldRenderLogged) {
                firstWorldRenderLogged = true;
                InjectionStatus.write("FIRST_WORLD_RENDER", "ClientHooks.renderWorld reached");
            }
        } catch (Throwable failure) {
            AgentLog.error("Live render bridge failed", failure);
        } finally {
            RENDERING.set(false);
        }
    }

    private static void pollKeybinds(RazorClient client) {
        for (Module module : client.getModuleManager().getModules()) {
            int key = module.getKeyCode();
            if (key == Keyboard.KEY_NONE) continue;
            boolean isDown;
            try {
                isDown = Keyboard.isKeyDown(key);
            } catch (Throwable ignored) {
                continue;
            }
            if (isDown && downKeys.add(Integer.valueOf(key))) {
                AgentLog.info("Keybind pressed: " + module.getName() + " key=" + key);
                client.onKey(key);
            } else if (!isDown) {
                downKeys.remove(Integer.valueOf(key));
            }
        }
    }

    private static void pollMouseButtons(RazorClient client) {
        try {
            if (!Mouse.isCreated()) return;
            for (int button = 0; button < downMouseButtons.length; button++) {
                boolean isDown = Mouse.isButtonDown(button);
                if (isDown == downMouseButtons[button]) continue;
                downMouseButtons[button] = isDown;
                MouseEvent event = new MouseEvent();
                event.button = button;
                event.buttonstate = isDown;
                event.dwheel = 0;
                event.dx = 0;
                event.dy = 0;
                client.getModuleManager().onMouseEvent(event);
            }
        } catch (Throwable failure) {
            AgentLog.error("Live mouse polling failed", failure);
        }
    }

    private static void logLiveSupport(RazorClient client) {
        if (supportLogged) return;
        supportLogged = true;
        StringBuilder builder = new StringBuilder("Live mode module coverage:");
        for (Module module : client.getModuleManager().getModules()) {
            String support = "runtime";
            String name = module.getName();
            if ("HUD".equals(name) || "BedPlates".equals(name) || "PlayerESP".equals(name) || "Trajectories".equals(name)) {
                support = "live-render";
            } else if ("KillAura".equals(name) || "AntiFireball".equals(name) || "Clutch".equals(name)) {
                support = "partial-rotation";
            }
            builder.append(' ').append(name).append('=').append(support).append(';');
        }
        AgentLog.info(builder.toString());
    }

    private static void rewriteMovementInput(EntityPlayerSP player) {
        if (player.movementInput != null) {
            LunarAccess.rewriteMovementInput(player.movementInput, null);
        }
    }

    private static void installPacketHandler(Minecraft mc) {
        if (mc.getNetHandler() == null || mc.getNetHandler().getNetworkManager() == null) {
            removePacketHandler();
            return;
        }
        final NetworkManager manager = mc.getNetHandler().getNetworkManager();
        if (manager == installedManager || manager == pendingManager) return;
        removePacketHandler();
        final Channel channel = manager.channel;
        if (channel == null) return;
        pendingManager = manager;
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!running || !isCurrentEpoch() || pendingManager != manager) return;
                    ChannelPipeline pipeline = channel.pipeline();
                    if (pipeline.get(INBOUND_HANDLER_NAME) != null) {
                        pipeline.remove(INBOUND_HANDLER_NAME);
                    }
                    if (pipeline.get(OUTBOUND_HANDLER_NAME) != null) {
                        pipeline.remove(OUTBOUND_HANDLER_NAME);
                    }
                    pipeline.addFirst(INBOUND_HANDLER_NAME, new LiveInboundHandler(manager));
                    pipeline.addFirst(OUTBOUND_HANDLER_NAME, new LiveOutboundHandler());
                    AgentLog.info("Installed live Netty packet handler");
                    InjectionStatus.write("NETTY_HANDLER_INSTALLED", "manager=" + manager);
                    installedManager = manager;
                } catch (Throwable failure) {
                    AgentLog.error("Failed to install live Netty packet handler", failure);
                } finally {
                    if (pendingManager == manager) pendingManager = null;
                }
            }
        });
    }

    private static boolean isCurrentEpoch() {
        return injectionId != null && injectionId.equals(System.getProperty("razorclient.live.epoch"));
    }

    private static void unloadParentEntrypoint() {
        ClassLoader own = LiveEntrypoint.class.getClassLoader();
        ClassLoader parent = own == null ? null : own.getParent();
        if (parent == null) {
            return;
        }
        try {
            Class<?> parentEntrypoint = Class.forName("com.razorclient.inject.LiveEntrypoint", false, parent);
            if (parentEntrypoint != LiveEntrypoint.class) {
                parentEntrypoint.getMethod("unload").invoke(null);
                AgentLog.info("Requested unload of parent LiveEntrypoint");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void removePacketHandler() {
        pendingManager = null;
        final NetworkManager manager = installedManager;
        installedManager = null;
        if (manager == null || manager.channel == null) return;
        manager.channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ChannelPipeline pipeline = manager.channel.pipeline();
                    if (pipeline.get(INBOUND_HANDLER_NAME) != null) {
                        pipeline.remove(INBOUND_HANDLER_NAME);
                    }
                    if (pipeline.get(OUTBOUND_HANDLER_NAME) != null) {
                        pipeline.remove(OUTBOUND_HANDLER_NAME);
                        AgentLog.info("Removed live Netty packet handler");
                    }
                } catch (Throwable failure) {
                    AgentLog.error("Failed to remove live Netty packet handler", failure);
                }
            }
        });
    }

    private static final class LiveOutboundHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof Packet && ClientHooks.outbound((Packet<?>) msg)) {
                if (promise != null) promise.setSuccess();
                return;
            }
            super.write(ctx, msg, promise);
        }
    }

    private static final class LiveInboundHandler extends ChannelInboundHandlerAdapter {
        private final NetworkManager manager;

        private LiveInboundHandler(NetworkManager manager) {
            this.manager = manager;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Packet) {
                INetHandler listener = manager.packetListener;
                if (listener != null && RazorClient.getInstance() != null
                    && RazorClient.getInstance().getPacketDelayManager().interceptInbound((Packet<?>) msg, listener)) {
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
    }

    private LiveEntrypoint() {}
}
