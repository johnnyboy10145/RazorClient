package com.razorclient.feature.module;

import com.razorclient.config.ConfigManager;
import com.razorclient.combat.CombatTargetService;
import com.razorclient.combat.CombatActionCoordinator;
import com.razorclient.util.MouseButtonHelper;
import com.razorclient.runtime.EntitySnapshotService;
import com.razorclient.runtime.EntityFrame;
import com.razorclient.runtime.FrameContext;
import com.razorclient.runtime.ClientSession;
import com.razorclient.runtime.ResourceArbiter;
import com.razorclient.runtime.RuntimeCore;
import com.razorclient.runtime.TickContext;
import com.razorclient.runtime.OwnerToken;
import com.razorclient.network.PacketDecision;
import com.razorclient.network.PacketLane;
import com.razorclient.network.PacketReleasePolicy;
import com.razorclient.runtime.capability.InputListener;
import com.razorclient.runtime.capability.RenderListener;
import com.razorclient.runtime.capability.TickListener;
import com.razorclient.runtime.capability.PacketPolicy;
import com.razorclient.feature.module.impl.AimAssistModule;
import com.razorclient.feature.module.impl.AutoClickerModule;
import com.razorclient.feature.module.impl.AntiBotModule;
import com.razorclient.feature.module.impl.AntiFireballModule;
import com.razorclient.feature.module.impl.BedPlatesModule;
import com.razorclient.feature.module.impl.BacktrackModule;
import com.razorclient.feature.module.impl.BlinkModule;
import com.razorclient.feature.module.impl.ClickGuiModule;
import com.razorclient.feature.module.impl.ClickRecorderModule;
import com.razorclient.feature.module.impl.ConfigModule;
import com.razorclient.feature.module.impl.FakeLagModule;
import com.razorclient.feature.module.impl.HudModule;
import com.razorclient.feature.module.impl.KillAuraModule;
import com.razorclient.feature.module.impl.ClutchModule;
import com.razorclient.feature.module.impl.KnockbackDelayModule;
import com.razorclient.feature.module.impl.LagRangeModule;
import com.razorclient.feature.module.impl.LegitScaffoldModule;
import com.razorclient.feature.module.impl.NoJumpDelayModule;
import com.razorclient.feature.module.impl.FastPlaceModule;
import com.razorclient.feature.module.impl.AutoToolModule;
import com.razorclient.feature.module.impl.AutoWeaponModule;
import com.razorclient.feature.module.impl.CriticalsModule;
import com.razorclient.feature.module.impl.HitSelectModule;
import com.razorclient.feature.module.impl.ItemPhysicsModule;
import com.razorclient.feature.module.impl.FullbrightModule;
import com.razorclient.feature.module.impl.NoHitDelayModule;
import com.razorclient.feature.module.impl.PingFixModule;
import com.razorclient.feature.module.impl.PlayerEspModule;
import com.razorclient.feature.module.impl.ReachModule;
import com.razorclient.feature.module.impl.RightClickerModule;
import com.razorclient.feature.module.impl.SelfDestructModule;
import com.razorclient.feature.module.impl.SprintModule;
import com.razorclient.feature.module.impl.SprintResetModule;
import com.razorclient.feature.module.impl.TeamsModule;
import com.razorclient.feature.module.impl.TrajectoriesModule;
import com.razorclient.feature.module.impl.VelocityModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<Module>();
    private final Map<Category, List<Module>> modulesByCategory = new EnumMap<Category, List<Module>>(Category.class);
    private final ConfigManager configManager;
    private final ConfigModule configModule;
    private final RuntimeCore runtimeCore = new RuntimeCore();
    private static final Module[] EMPTY_SUBSCRIBERS = new Module[0];
    private volatile Module[] clientTickSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] clientTickEventSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] playerTickSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] mouseSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] jumpSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] renderTickSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] renderWorldSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] renderOverlaySubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] sessionResetSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] inputLossSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] packetPolicySubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] inboundQueuedSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] inboundReleasedSubscribers = EMPTY_SUBSCRIBERS;
    private volatile Module[] inboundProcessedSubscribers = EMPTY_SUBSCRIBERS;

    public ModuleManager() {
        for (Category category : Category.values()) {
            modulesByCategory.put(category, new ArrayList<Module>());
        }

        register(new SprintModule());
        register(new BedPlatesModule());
        register(new LegitScaffoldModule());
        register(new NoJumpDelayModule());
        register(new FastPlaceModule());
        register(new AutoToolModule());
        register(new AutoWeaponModule());
        register(new HitSelectModule());
        register(new NoHitDelayModule());
        register(new AutoClickerModule());
        register(new RightClickerModule());
        register(new ReachModule());
        register(new AntiBotModule());
        register(new TeamsModule());
        register(new AntiFireballModule());
        register(new AimAssistModule());
        register(new KillAuraModule());
        register(new SprintResetModule());
        register(new CriticalsModule());
        register(new KnockbackDelayModule());
        register(new VelocityModule());
        register(new FakeLagModule());
        register(new BlinkModule());
        register(new BacktrackModule());
        register(new LagRangeModule());
        register(new PingFixModule());
        register(new ClutchModule());
        register(new ClickRecorderModule());
        register(new ClickGuiModule());
        register(new PlayerEspModule());
        register(new ItemPhysicsModule());
        register(new FullbrightModule());
        register(new HudModule());
        register(new TrajectoriesModule());
        register(new SelfDestructModule());
        configManager = new ConfigManager(this);
        configModule = new ConfigModule(configManager);
        register(configModule);
        configManager.initialize();
    }

    private void register(Module module) {
        module.attachRuntime(runtimeCore);
        modules.add(module);
        modulesByCategory.get(module.getCategory()).add(module);
        rebuildSubscriberIndexes();
    }

    /** Publishes immutable callback arrays; dispatch never scans modules that cannot receive an event. */
    private void rebuildSubscriberIndexes() {
        clientTickSubscribers = subscribers(TickListener.class, "onClientTick");
        clientTickEventSubscribers = subscribers(null, "onClientTick", TickEvent.ClientTickEvent.class);
        playerTickSubscribers = subscribers(null, "onPlayerTick", TickEvent.PlayerTickEvent.class);
        mouseSubscribers = subscribers(null, "onMouseEvent", MouseEvent.class);
        jumpSubscribers = subscribers(null, "onPlayerJump", LivingEvent.LivingJumpEvent.class);
        renderTickSubscribers = subscribers(RenderListener.class, "onRenderTick", TickEvent.RenderTickEvent.class);
        renderWorldSubscribers = subscribers(RenderListener.class, "onRenderWorld", RenderWorldLastEvent.class);
        renderOverlaySubscribers = subscribers(RenderListener.class, "onRenderOverlay", RenderGameOverlayEvent.Text.class);
        sessionResetSubscribers = subscribersAny(null,
            new MethodSignature("onSessionReset"),
            new MethodSignature("onSessionReset", ModuleResetReason.class));
        inputLossSubscribers = subscribersAny(InputListener.class,
            new MethodSignature("onInputContextLost"),
            new MethodSignature("onInputContextLost", ModuleResetReason.class));
        packetPolicySubscribers = subscribersAny(PacketPolicy.class,
            new MethodSignature("onOutboundPacket", Packet.class),
            new MethodSignature("onInboundPacket", Packet.class),
            new MethodSignature("getOutboundPacketDelay", Packet.class),
            new MethodSignature("getInboundPacketDelay", Packet.class),
            new MethodSignature("getOutboundPacketDelayPriority", Packet.class),
            new MethodSignature("getInboundPacketDelayPriority", Packet.class),
            new MethodSignature("shouldHoldOutboundPacket", Packet.class),
            new MethodSignature("shouldHoldInboundPacket", Packet.class),
            new MethodSignature("shouldCancelInboundPacket", Packet.class),
            new MethodSignature("shouldBypassOutboundOrdering", Packet.class),
            new MethodSignature("shouldFlushThenPassOutboundPacket", Packet.class),
            new MethodSignature("receivesInboundPacketsWhenDisabled"),
            new MethodSignature("isPacketDelayActive"),
            new MethodSignature("isOutboundPacketDelayActive"),
            new MethodSignature("isInboundPacketDelayActive"),
            new MethodSignature("onPacketDelayOverflow", boolean.class),
            new MethodSignature("consumeFlushRequest"),
            new MethodSignature("consumeOutboundFlushRequest"),
            new MethodSignature("consumeInboundFlushRequest"));
        inboundQueuedSubscribers = subscribers(null, "onInboundPacketQueued", Packet.class);
        inboundReleasedSubscribers = subscribers(null, "onInboundPacketReleased", Packet.class);
        inboundProcessedSubscribers = subscribers(null, "onInboundPacketProcessed", Packet.class);
    }

    private Module[] subscribers(Class<?> capability, String methodName, Class<?>... parameterTypes) {
        List<Module> selected = new ArrayList<Module>();
        for (Module module : modules) {
            if ((capability != null && capability.isInstance(module))
                    || overrides(module, methodName, parameterTypes)) selected.add(module);
        }
        return selected.toArray(new Module[selected.size()]);
    }

    private Module[] subscribersAny(Class<?> capability, MethodSignature... signatures) {
        List<Module> selected = new ArrayList<Module>();
        for (Module module : modules) {
            boolean subscribed = capability != null && capability.isInstance(module);
            for (int index = 0; !subscribed && index < signatures.length; index++) {
                MethodSignature signature = signatures[index];
                subscribed = overrides(module, signature.name, signature.parameterTypes);
            }
            if (subscribed) selected.add(module);
        }
        return selected.toArray(new Module[selected.size()]);
    }

    private static boolean overrides(Module module, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = module.getClass().getMethod(methodName, parameterTypes);
            return method.getDeclaringClass() != Module.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static final class MethodSignature {
        private final String name;
        private final Class<?>[] parameterTypes;

        private MethodSignature(String name, Class<?>... parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes;
        }
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<Module> getModules(Category category) {
        return Collections.unmodifiableList(modulesByCategory.get(category));
    }

    public int getPacketPolicyModuleCount() { return packetPolicySubscribers.length; }
    public Module getPacketPolicyModule(int index) { return packetPolicySubscribers[index]; }

    public <T extends Module> T getModule(Class<T> moduleClass) {
        for (Module module : modules) {
            if (moduleClass.isInstance(module)) {
                return moduleClass.cast(module);
            }
        }
        return null;
    }

    public void onClientTick() {
        updateLifecycleState();
        for (Module module : clientTickSubscribers) {
            if (module.isEnabled()) {
                if (module instanceof TickListener) invokeTickListener(module);
                else invokeCallback(module, CallbackKind.CLIENT_TICK, null);
            }
        }
        ConfigManager.flushPendingSave();
    }

    /** Runs on every live pulse so paused ticks cannot retain input/slot/timer state. */
    public void pollLifecycleState() {
        updateLifecycleState();
    }

    /** Returns true once per actual Minecraft tick, even when the live scheduler pulses faster. */
    public boolean beginRealTick() {
        return runtimeCore.beginRealTick(Minecraft.getMinecraft());
    }

    public FrameContext beginFrame(float partialTicks) {
        return runtimeCore.beginFrame(Minecraft.getMinecraft(), partialTicks);
    }

    public TickContext getTickContext() { return runtimeCore.getTickContext(); }
    public FrameContext getFrameContext() { return runtimeCore.getFrameContext(); }
    public EntitySnapshotService getEntitySnapshots() { return runtimeCore.getEntitySnapshots(); }
    public EntityFrame getEntityFrame() { return runtimeCore.getEntityFrame(); }
    public ResourceArbiter getResourceArbiter() { return runtimeCore.getResourceArbiter(); }
    public ClientSession getClientSession() { return runtimeCore.getClientSession(); }
    public boolean scheduleClientTask(Runnable action) {
        return runtimeCore.getClientSession().getScheduler().submit(null, action);
    }

    private void updateLifecycleState() {
        Minecraft minecraft = Minecraft.getMinecraft();
        ClientSession.Observation observation = runtimeCore.observeSession(minecraft);
        if (observation.getResetReason() != null) resetEnabledModules(observation.getResetReason());
        if (observation.isInputLost()) {
            ModuleResetReason reason = observation.getInputReason();
            CombatActionCoordinator.clear();
            MouseButtonHelper.releaseAllSynthetic();
            for (Module module : modules) {
                if (module.isEnabled()) module.cleanupInputScope(reason);
            }
            for (Module module : inputLossSubscribers) {
                if (!module.isEnabled()) continue;
                if (module instanceof InputListener) invokeInputListener(module, reason);
                else invokeCallback(module, CallbackKind.INPUT_CONTEXT_LOST, reason);
            }
        }
    }

    private void resetEnabledModules(ModuleResetReason reason) {
        CombatTargetService.clear();
        runtimeCore.getTargetPublications().clearAll();
        CombatActionCoordinator.clear();
        MouseButtonHelper.releaseAllSynthetic();
        // Every enabled module owns timers, leases and cached decisions through
        // its scope, not only modules that participate in packet policy.
        for (Module module : modules) {
            if (module.isEnabled()) module.resetScope(reason);
        }
        for (Module module : sessionResetSubscribers) {
            if (module.isEnabled()) invokeCallback(module, CallbackKind.SESSION_RESET, reason);
        }
    }

    public void onClientTick(TickEvent.ClientTickEvent event) {
        for (Module module : clientTickEventSubscribers) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.CLIENT_TICK_EVENT, event);
            }
        }
    }

    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        for (Module module : playerTickSubscribers) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.PLAYER_TICK, event);
            }
        }
    }

    public void onMouseEvent(MouseEvent event) {
        for (Module module : mouseSubscribers) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.MOUSE, event);
            }
        }
    }

    public void onPlayerJump(LivingEvent.LivingJumpEvent event) {
        for (Module module : jumpSubscribers) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.PLAYER_JUMP, event);
            }
        }
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
        for (Module module : renderTickSubscribers) {
            if (module.isEnabled()) {
                if (module instanceof RenderListener) invokeRenderListener(module, RenderListener.Phase.FRAME);
                else invokeCallback(module, CallbackKind.RENDER_TICK, event);
            }
        }
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
        for (Module module : renderWorldSubscribers) {
            if (module.isEnabled()) {
                if (module instanceof RenderListener) invokeRenderListener(module, RenderListener.Phase.WORLD);
                else invokeCallback(module, CallbackKind.RENDER_WORLD, event);
            }
        }
    }

    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        for (Module module : renderOverlaySubscribers) {
            if (module.isEnabled()) {
                if (module instanceof RenderListener) invokeRenderListener(module, RenderListener.Phase.OVERLAY);
                else invokeCallback(module, CallbackKind.RENDER_OVERLAY, event);
            }
        }
    }

    public int getOutboundPacketDelay(Packet<?> packet) {
        return selectOutboundPacketDelay(packet).getDelay();
    }

    public PacketDelaySelection selectOutboundPacketDelay(Packet<?> packet) {
        return selectPacketDelay(packet, true);
    }

    public PacketDelaySelection selectInboundPacketDelay(Packet<?> packet) {
        return selectPacketDelay(packet, false);
    }

    /** Captures all outbound policy state exactly once on the Netty interception edge. */
    public PacketDecision captureOutboundPacketDecision(Packet<?> packet) {
        onOutboundPacket(packet);
        PacketDelaySelection selection = selectPacketDelay(packet, true);
        PacketDecision decision = createPacketDecision(packet, true, selection, false);
        Module owner = selection.getOwner();
        if (owner != null) {
            try {
                if (owner.shouldFlushThenPassOutboundPacket(packet)) {
                    return new PacketDecision(PacketDecision.Action.FLUSH_THEN_PASS, owner,
                        owner.getScope().getOwnerToken(), selection.getPriority(), 0,
                        PacketReleasePolicy.FLUSH_THEN_PASS, decision.getLane());
                }
            } catch (Throwable failure) {
                runtimeCore.getFaultBarrier().report(owner, "flush-then-pass", failure);
            }
        }
        return decision;
    }

    /** Captures mutation/cancellation/hold state once; release never re-queries module settings. */
    public PacketDecision captureInboundPacketDecision(Packet<?> packet) {
        onInboundPacket(packet);
        PacketDelaySelection selection = selectInboundPacketDelay(packet);
        boolean cancel = shouldCancelInboundPacket(packet);
        return createPacketDecision(packet, false, selection, cancel);
    }

    private PacketDecision capturePacketDecision(Packet<?> packet, boolean outbound, boolean cancel) {
        return createPacketDecision(packet, outbound, selectPacketDelay(packet, outbound), cancel);
    }

    private PacketDecision createPacketDecision(Packet<?> packet, boolean outbound,
            PacketDelaySelection selection, boolean cancel) {
        Module owner = selection.getOwner();
        OwnerToken token = owner == null ? null : owner.getScope().getOwnerToken();
        boolean hold = selection.isIndefinite() || selection.getDelay() > 0;
        PacketDecision.Action action = hold ? PacketDecision.Action.HOLD
            : cancel ? PacketDecision.Action.CANCEL : PacketDecision.Action.PASS;
        PacketReleasePolicy release = selection.isIndefinite() ? PacketReleasePolicy.EXPLICIT_FLUSH
            : hold ? PacketReleasePolicy.DEADLINE : PacketReleasePolicy.IMMEDIATE;
        PacketLane.Direction direction = outbound ? PacketLane.Direction.OUTBOUND : PacketLane.Direction.INBOUND;
        PacketLane lane = new PacketLane(token, direction, packetDependencyDomain(packet, outbound));
        return new PacketDecision(action, owner, token, selection.getPriority(), selection.getDelay(),
            release, lane, cancel);
    }

    private static String packetDependencyDomain(Packet<?> packet, boolean outbound) {
        if (packet == null) return "transport";
        String name = packet.getClass().getSimpleName();
        if (outbound && name.startsWith("C03")) return "movement";
        if (outbound && (name.startsWith("C02") || name.startsWith("C07") || name.startsWith("C08"))) return "action";
        if (!outbound && packet instanceof S12PacketEntityVelocity) {
            return "knockback:" + ((S12PacketEntityVelocity) packet).getEntityID();
        }
        if (!outbound && packet instanceof S14PacketEntity) {
            return "entity-movement:" + ((S14PacketEntity) packet).entityId;
        }
        if (!outbound && packet instanceof S18PacketEntityTeleport) {
            return "entity-movement:" + ((S18PacketEntityTeleport) packet).entityId;
        }
        if (!outbound && name.startsWith("S27")) return "knockback:local";
        if (!outbound && name.startsWith("S19")) return "entity-status";
        return "protocol";
    }

    private PacketDelaySelection selectPacketDelay(Packet<?> packet, boolean outbound) {
        Module selected = null;
        int selectedDelay = 0;
        int selectedPriority = Integer.MIN_VALUE;
        boolean selectedHold = false;
        for (Module module : packetPolicySubscribers) {
            if (!module.isEnabled()) {
                continue;
            }

            int delay;
            boolean hold;
            int priority;
            try {
                delay = outbound ? module.getOutboundPacketDelay(packet) : module.getInboundPacketDelay(packet);
                hold = outbound ? module.shouldHoldOutboundPacket(packet) : module.shouldHoldInboundPacket(packet);
                priority = outbound ? module.getOutboundPacketDelayPriority(packet) : module.getInboundPacketDelayPriority(packet);
            } catch (Throwable failure) {
                runtimeCore.getFaultBarrier().report(module, outbound ? "outbound-delay" : "inbound-delay", failure);
                continue;
            }
            if (delay <= 0 && !hold) {
                continue;
            }
            if (selected == null || priority > selectedPriority) {
                selected = module;
                selectedDelay = delay;
                selectedPriority = priority;
                selectedHold = hold;
            }
        }
        return new PacketDelaySelection(selected, selectedDelay, selectedPriority, selectedHold);
    }

    public void onOutboundPacket(Packet<?> packet) {
        for (Module module : packetPolicySubscribers) {
            if (module.isEnabled()) {
                invokePacketCallback(module, "outbound-packet", packet, 0);
            }
        }
    }

    public void onInboundPacket(Packet<?> packet) {
        for (Module module : packetPolicySubscribers) {
            if (module.isEnabled() || module.receivesInboundPacketsWhenDisabled()) {
                invokePacketCallback(module, "inbound-packet", packet, 1);
            }
        }
    }

    public boolean shouldCancelInboundPacket(Packet<?> packet) {
        for (Module module : packetPolicySubscribers) {
            if (module.isEnabled()) {
                try {
                    if (module.shouldCancelInboundPacket(packet)) return true;
                } catch (Throwable failure) {
                    runtimeCore.getFaultBarrier().report(module, "cancel-inbound", failure);
                }
            }
        }
        return false;
    }

    public void onInboundPacketQueued(Packet<?> packet) {
        for (Module module : inboundQueuedSubscribers) {
            invokePacketCallback(module, "inbound-queued", packet, 2);
        }
    }

    public void onInboundPacketReleased(Packet<?> packet) {
        for (Module module : inboundReleasedSubscribers) {
            invokePacketCallback(module, "inbound-released", packet, 3);
        }
    }

    public void onInboundPacketProcessed(Packet<?> packet) {
        for (Module module : inboundProcessedSubscribers) {
            invokePacketCallback(module, "inbound-processed", packet, 4);
        }
    }

    public int consumePacketFlushRequests(Module module) {
        if (module == null) return 0;
        try {
            int requests = module.consumeFlushRequest() ? 1 : 0;
            if (module.consumeOutboundFlushRequest()) requests |= 2;
            if (module.consumeInboundFlushRequest()) requests |= 4;
            return requests;
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, "consume-packet-flush", failure);
            return 0;
        }
    }

    public boolean isPacketDelayOwnerActive(Module module, boolean outbound) {
        if (module == null || !module.isEnabled()) return false;
        try {
            return outbound ? module.isOutboundPacketDelayActive() : module.isInboundPacketDelayActive();
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, "packet-owner-active", failure);
            return false;
        }
    }

    public boolean shouldBypassOutboundOrdering(Module module, Packet<?> packet) {
        if (module == null) return false;
        try {
            return module.shouldBypassOutboundOrdering(packet);
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, "packet-order-bypass", failure);
            return false;
        }
    }

    public void onPacketDelayOverflow(Module module, boolean outbound) {
        if (module == null) return;
        try {
            module.onPacketDelayOverflow(outbound);
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, "packet-overflow", failure);
        }
    }

    public int getInboundPacketDelay(Packet<?> packet) {
        return selectInboundPacketDelay(packet).getDelay();
    }

    public boolean isPacketDelayActive() {
        for (Module module : packetPolicySubscribers) {
            if (module.isEnabled()) {
                try {
                    if (module.isPacketDelayActive()) return true;
                } catch (Throwable failure) {
                    runtimeCore.getFaultBarrier().report(module, "packet-delay-active", failure);
                }
            }
        }
        return false;
    }

    public boolean isOutboundPacketDelayActive() {
        for (Module module : packetPolicySubscribers) {
            if (module.isEnabled()) {
                try {
                    if (module.isOutboundPacketDelayActive()) return true;
                } catch (Throwable failure) {
                    runtimeCore.getFaultBarrier().report(module, "outbound-delay-active", failure);
                }
            }
        }
        return false;
    }

    public boolean isOutboundPacketDelayOwnerActive(String ownerName) {
        for (Module module : modules) {
            if (module.isEnabled() && module.getName().equals(ownerName)) {
                try {
                    if (module.isOutboundPacketDelayActive()) return true;
                } catch (Throwable failure) {
                    runtimeCore.getFaultBarrier().report(module, "outbound-owner-active", failure);
                }
            }
        }
        return false;
    }

    public boolean isInboundPacketDelayActive() {
        for (Module module : packetPolicySubscribers) {
            if (module.isEnabled()) {
                try {
                    if (module.isInboundPacketDelayActive()) return true;
                } catch (Throwable failure) {
                    runtimeCore.getFaultBarrier().report(module, "inbound-delay-active", failure);
                }
            }
        }
        return false;
    }

    public boolean isInboundPacketDelayOwnerActive(String ownerName) {
        for (Module module : modules) {
            if (module.isEnabled() && module.getName().equals(ownerName)) {
                try {
                    if (module.isInboundPacketDelayActive()) return true;
                } catch (Throwable failure) {
                    runtimeCore.getFaultBarrier().report(module, "inbound-owner-active", failure);
                }
            }
        }
        return false;
    }

    public boolean consumeFlushRequest() {
        for (Module module : packetPolicySubscribers) {
            try {
                if (module.consumeFlushRequest()) return true;
            } catch (Throwable failure) {
                runtimeCore.getFaultBarrier().report(module, "consume-flush", failure);
            }
        }
        return false;
    }

    public boolean consumeOutboundFlushRequest() {
        for (Module module : packetPolicySubscribers) {
            try {
                if (module.consumeOutboundFlushRequest()) return true;
            } catch (Throwable failure) {
                runtimeCore.getFaultBarrier().report(module, "consume-outbound-flush", failure);
            }
        }
        return false;
    }

    public boolean consumeInboundFlushRequest() {
        for (Module module : packetPolicySubscribers) {
            try {
                if (module.consumeInboundFlushRequest()) return true;
            } catch (Throwable failure) {
                runtimeCore.getFaultBarrier().report(module, "consume-inbound-flush", failure);
            }
        }
        return false;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public void refreshConfigModule() {
        configModule.rebuildSettings();
    }

    private void invokeTickListener(Module module) {
        try {
            ((TickListener) module).onTick(module.getContext());
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, "typed-client-tick", failure);
        }
    }

    private void invokeRenderListener(Module module, RenderListener.Phase phase) {
        try {
            ((RenderListener) module).onRender(module.getContext(), runtimeCore.getFrameContext(), phase);
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, "typed-render-" + phase.name().toLowerCase(), failure);
        }
    }

    private void invokeInputListener(Module module, ModuleResetReason reason) {
        try {
            ((InputListener) module).onInputUnavailable(module.getContext(), reason);
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, "typed-input-unavailable", failure);
        }
    }

    private void invokeCallback(Module module, CallbackKind callback, Object argument) {
        try {
            switch (callback) {
                case CLIENT_TICK:
                    module.onClientTick();
                    break;
                case CLIENT_TICK_EVENT:
                    module.onClientTick((TickEvent.ClientTickEvent) argument);
                    break;
                case PLAYER_TICK:
                    module.onPlayerTick((TickEvent.PlayerTickEvent) argument);
                    break;
                case MOUSE:
                    module.onMouseEvent((MouseEvent) argument);
                    break;
                case PLAYER_JUMP:
                    module.onPlayerJump((LivingEvent.LivingJumpEvent) argument);
                    break;
                case RENDER_TICK:
                    module.onRenderTick((TickEvent.RenderTickEvent) argument);
                    break;
                case RENDER_WORLD:
                    module.onRenderWorld((RenderWorldLastEvent) argument);
                    break;
                case RENDER_OVERLAY:
                    module.onRenderOverlay((RenderGameOverlayEvent.Text) argument);
                    break;
                case SESSION_RESET:
                    module.onSessionReset((ModuleResetReason) argument);
                    break;
                case INPUT_CONTEXT_LOST:
                    module.onInputContextLost((ModuleResetReason) argument);
                    break;
                default:
                    break;
            }
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, callback.label, failure);
        }
    }

    private void invokePacketCallback(Module module, String callback, Packet<?> packet, int kind) {
        try {
            switch (kind) {
                case 0:
                    module.onOutboundPacket(packet);
                    break;
                case 1:
                    module.onInboundPacket(packet);
                    break;
                case 2:
                    module.onInboundPacketQueued(packet);
                    break;
                case 3:
                    module.onInboundPacketReleased(packet);
                    break;
                case 4:
                    module.onInboundPacketProcessed(packet);
                    break;
                default:
                    break;
            }
        } catch (Throwable failure) {
            runtimeCore.getFaultBarrier().report(module, callback, failure);
        }
    }

    public void shutdownForUnload() {
        ConfigManager.flushPendingSaveNow();
        CombatActionCoordinator.clear();
        MouseButtonHelper.releaseAllSynthetic();
        for (Module module : new ArrayList<Module>(modules)) {
            module.forceDisableForUnload();
        }
        runtimeCore.reset();
    }

    private enum CallbackKind {
        CLIENT_TICK("client-tick"),
        CLIENT_TICK_EVENT("client-tick-event"),
        PLAYER_TICK("player-tick"),
        MOUSE("mouse"),
        PLAYER_JUMP("player-jump"),
        RENDER_TICK("render-tick"),
        RENDER_WORLD("render-world"),
        RENDER_OVERLAY("render-overlay"),
        SESSION_RESET("session-reset"),
        INPUT_CONTEXT_LOST("input-context-lost");

        private final String label;
        CallbackKind(String label) { this.label = label; }
    }

    public static final class PacketDelaySelection {
        private final Module owner;
        private final int delay;
        private final int priority;
        private final boolean indefinite;

        private PacketDelaySelection(Module owner, int delay, int priority, boolean indefinite) {
            this.owner = owner;
            this.delay = delay;
            this.priority = priority;
            this.indefinite = indefinite;
        }

        public Module getOwner() {
            return owner;
        }

        public String getOwnerName() {
            return owner == null ? "None" : owner.getName();
        }

        public int getDelay() {
            return delay;
        }

        public int getPriority() {
            return priority;
        }

        public boolean isIndefinite() {
            return indefinite;
        }
    }
}
