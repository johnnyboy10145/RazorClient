package com.razorclient.feature.module;

import com.razorclient.config.ConfigManager;
import com.razorclient.combat.CombatTargetService;
import com.razorclient.combat.CombatActionCoordinator;
import com.razorclient.util.MouseButtonHelper;
import com.razorclient.runtime.EntitySnapshotService;
import com.razorclient.runtime.FrameContext;
import com.razorclient.runtime.ResourceArbiter;
import com.razorclient.runtime.RuntimeCore;
import com.razorclient.runtime.TickContext;
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
import net.minecraft.network.Packet;
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
    private Object lastWorld;
    private Object lastPlayer;
    private boolean lastPlayerDead;
    private boolean inputContextLost = true;

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
        module.attachRuntime(runtimeCore.getResourceArbiter());
        modules.add(module);
        modulesByCategory.get(module.getCategory()).add(module);
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<Module> getModules(Category category) {
        return Collections.unmodifiableList(modulesByCategory.get(category));
    }

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
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.CLIENT_TICK, null);
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
    public ResourceArbiter getResourceArbiter() { return runtimeCore.getResourceArbiter(); }

    private void updateLifecycleState() {
        Minecraft minecraft = Minecraft.getMinecraft();
        Object world = minecraft == null ? null : minecraft.theWorld;
        Object player = minecraft == null ? null : minecraft.thePlayer;
        boolean playerDead = minecraft != null && minecraft.thePlayer != null && minecraft.thePlayer.isDead;
        if (world != lastWorld || player != lastPlayer) {
            ModuleResetReason reason = world == null ? ModuleResetReason.DISCONNECT
                : lastWorld == world ? ModuleResetReason.RESPAWN : ModuleResetReason.WORLD_CHANGE;
            lastWorld = world;
            lastPlayer = player;
            lastPlayerDead = playerDead;
            resetEnabledModules(reason);
        } else if (playerDead != lastPlayerDead) {
            lastPlayerDead = playerDead;
            resetEnabledModules(ModuleResetReason.RESPAWN);
        }

        boolean lost = minecraft == null || world == null || player == null
            || playerDead || !minecraft.inGameHasFocus || minecraft.currentScreen != null;
        if (lost && !inputContextLost) {
            ModuleResetReason reason = minecraft != null && minecraft.currentScreen != null
                ? ModuleResetReason.GUI_OPENED : ModuleResetReason.FOCUS_LOSS;
            CombatActionCoordinator.clear();
            MouseButtonHelper.releaseAllSynthetic();
            for (Module module : modules) {
                if (module.isEnabled()) {
                    module.cleanupInputScope(reason);
                    invokeCallback(module, CallbackKind.INPUT_CONTEXT_LOST, reason);
                }
            }
        }
        inputContextLost = lost;
    }

    private void resetEnabledModules(ModuleResetReason reason) {
        CombatTargetService.clear();
        CombatActionCoordinator.clear();
        MouseButtonHelper.releaseAllSynthetic();
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.resetScope(reason);
                invokeCallback(module, CallbackKind.SESSION_RESET, reason);
            }
        }
    }

    public void onClientTick(TickEvent.ClientTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.CLIENT_TICK_EVENT, event);
            }
        }
    }

    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.PLAYER_TICK, event);
            }
        }
    }

    public void onMouseEvent(MouseEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.MOUSE, event);
            }
        }
    }

    public void onPlayerJump(LivingEvent.LivingJumpEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.PLAYER_JUMP, event);
            }
        }
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.RENDER_TICK, event);
            }
        }
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.RENDER_WORLD, event);
            }
        }
    }

    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokeCallback(module, CallbackKind.RENDER_OVERLAY, event);
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

    private PacketDelaySelection selectPacketDelay(Packet<?> packet, boolean outbound) {
        Module selected = null;
        int selectedDelay = 0;
        int selectedPriority = Integer.MIN_VALUE;
        boolean selectedHold = false;
        for (Module module : modules) {
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
        for (Module module : modules) {
            if (module.isEnabled()) {
                invokePacketCallback(module, "outbound-packet", packet, 0);
            }
        }
    }

    public void onInboundPacket(Packet<?> packet) {
        for (Module module : modules) {
            if (module.isEnabled() || module.receivesInboundPacketsWhenDisabled()) {
                invokePacketCallback(module, "inbound-packet", packet, 1);
            }
        }
    }

    public boolean shouldCancelInboundPacket(Packet<?> packet) {
        for (Module module : modules) {
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
        for (Module module : modules) {
            invokePacketCallback(module, "inbound-queued", packet, 2);
        }
    }

    public void onInboundPacketReleased(Packet<?> packet) {
        for (Module module : modules) {
            invokePacketCallback(module, "inbound-released", packet, 3);
        }
    }

    public void onInboundPacketProcessed(Packet<?> packet) {
        for (Module module : modules) {
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
        for (Module module : modules) {
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
        for (Module module : modules) {
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
        for (Module module : modules) {
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
        for (Module module : modules) {
            try {
                if (module.consumeFlushRequest()) return true;
            } catch (Throwable failure) {
                runtimeCore.getFaultBarrier().report(module, "consume-flush", failure);
            }
        }
        return false;
    }

    public boolean consumeOutboundFlushRequest() {
        for (Module module : modules) {
            try {
                if (module.consumeOutboundFlushRequest()) return true;
            } catch (Throwable failure) {
                runtimeCore.getFaultBarrier().report(module, "consume-outbound-flush", failure);
            }
        }
        return false;
    }

    public boolean consumeInboundFlushRequest() {
        for (Module module : modules) {
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
