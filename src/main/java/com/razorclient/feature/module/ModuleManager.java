package com.razorclient.feature.module;

import com.razorclient.config.ConfigManager;
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
import com.razorclient.feature.module.impl.PingFixModule;
import com.razorclient.feature.module.impl.PlayerEspModule;
import com.razorclient.feature.module.impl.ReachModule;
import com.razorclient.feature.module.impl.RightClickerModule;
import com.razorclient.feature.module.impl.SelfDestructModule;
import com.razorclient.feature.module.impl.SprintModule;
import com.razorclient.feature.module.impl.TrajectoriesModule;
import com.razorclient.feature.module.impl.VelocityModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.Packet;
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

    public ModuleManager() {
        for (Category category : Category.values()) {
            modulesByCategory.put(category, new ArrayList<Module>());
        }

        register(new SprintModule());
        register(new BedPlatesModule());
        register(new LegitScaffoldModule());
        register(new AutoClickerModule());
        register(new RightClickerModule());
        register(new ReachModule());
        register(new AntiBotModule());
        register(new AntiFireballModule());
        register(new AimAssistModule());
        register(new KillAuraModule());
        register(new KnockbackDelayModule());
        register(new VelocityModule());
        register(new FakeLagModule());
        register(new BlinkModule());
        register(new BacktrackModule());
        register(new LagRangeModule());
        register(new PingFixModule());
        register(ClutchModule.getInstance());
        register(new ClickRecorderModule());
        register(new ClickGuiModule());
        register(new PlayerEspModule());
        register(new HudModule());
        register(new TrajectoriesModule());
        register(new SelfDestructModule());
        configManager = new ConfigManager(this);
        configModule = new ConfigModule(configManager);
        register(configModule);
        configManager.initialize();
    }

    private void register(Module module) {
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
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onClientTick();
            }
        }
    }

    public void onClientTick(TickEvent.ClientTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onClientTick(event);
            }
        }
    }

    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onPlayerTick(event);
            }
        }
    }

    public void onMouseEvent(MouseEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onMouseEvent(event);
            }
        }
    }

    public void onPlayerJump(LivingEvent.LivingJumpEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onPlayerJump(event);
            }
        }
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderTick(event);
            }
        }
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderWorld(event);
            }
        }
    }

    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderOverlay(event);
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
        for (Module module : modules) {
            if (!module.isEnabled()) {
                continue;
            }

            int delay = outbound ? module.getOutboundPacketDelay(packet) : module.getInboundPacketDelay(packet);
            if (delay <= 0) {
                continue;
            }
            int priority = outbound ? module.getOutboundPacketDelayPriority(packet) : module.getInboundPacketDelayPriority(packet);
            if (selected == null || priority > selectedPriority) {
                selected = module;
                selectedDelay = delay;
                selectedPriority = priority;
            }
        }
        return new PacketDelaySelection(selected, selectedDelay, selectedPriority);
    }

    public void onOutboundPacket(Packet<?> packet) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onOutboundPacket(packet);
            }
        }
    }

    public void onInboundPacket(Packet<?> packet) {
        for (Module module : modules) {
            if (module.isEnabled() || module.receivesInboundPacketsWhenDisabled()) {
                module.onInboundPacket(packet);
            }
        }
    }

    public boolean shouldCancelInboundPacket(Packet<?> packet) {
        for (Module module : modules) {
            if (module.isEnabled() && module.shouldCancelInboundPacket(packet)) {
                return true;
            }
        }
        return false;
    }

    public void onInboundPacketQueued(Packet<?> packet) {
        for (Module module : modules) {
            module.onInboundPacketQueued(packet);
        }
    }

    public void onInboundPacketReleased(Packet<?> packet) {
        for (Module module : modules) {
            module.onInboundPacketReleased(packet);
        }
    }

    public int getInboundPacketDelay(Packet<?> packet) {
        return selectInboundPacketDelay(packet).getDelay();
    }

    public boolean isPacketDelayActive() {
        for (Module module : modules) {
            if (module.isEnabled() && module.isPacketDelayActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean isOutboundPacketDelayActive() {
        for (Module module : modules) {
            if (module.isEnabled() && module.isOutboundPacketDelayActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean isInboundPacketDelayActive() {
        for (Module module : modules) {
            if (module.isEnabled() && module.isInboundPacketDelayActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeFlushRequest() {
        for (Module module : modules) {
            if (module.consumeFlushRequest()) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeOutboundFlushRequest() {
        for (Module module : modules) {
            if (module.consumeOutboundFlushRequest()) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeInboundFlushRequest() {
        for (Module module : modules) {
            if (module.consumeInboundFlushRequest()) {
                return true;
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

    public void shutdownForUnload() {
        for (Module module : new ArrayList<Module>(modules)) {
            module.forceDisableForUnload();
        }
    }

    public static final class PacketDelaySelection {
        private final Module owner;
        private final int delay;
        private final int priority;

        private PacketDelaySelection(Module owner, int delay, int priority) {
            this.owner = owner;
            this.delay = delay;
            this.priority = priority;
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
    }
}
