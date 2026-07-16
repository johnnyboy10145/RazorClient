package com.razorclient.feature.module;

import com.razorclient.config.ConfigManager;
import com.razorclient.feature.setting.Setting;
import com.razorclient.inject.AgentLog;
import com.razorclient.runtime.ModuleScope;
import com.razorclient.runtime.ModuleContext;
import com.razorclient.runtime.RuntimeCore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.Packet;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public abstract class Module {
    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting> settings = new ArrayList<Setting>();
    private final ModuleScope scope;
    private ModuleContext context;
    private volatile int keyCode;
    private volatile boolean enabled;
    private volatile long enableGeneration;

    protected Module(String name, String description, Category category, int keyCode) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keyCode = keyCode;
        this.scope = new ModuleScope(name);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    protected void addSetting(Setting setting) {
        settings.add(setting);
    }

    protected void clearSettings() {
        settings.clear();
    }

    public List<Setting> getSettings() {
        return Collections.unmodifiableList(settings);
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_NONE && !canBeUnbound()) {
            return;
        }
        this.keyCode = keyCode;
        ConfigManager.saveActiveConfig();
    }

    public boolean canBeUnbound() {
        return true;
    }

    public boolean showsKeybindSetting() {
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getEnableGeneration() {
        return enableGeneration;
    }

    public String getHudInfo() {
        return "";
    }

    public ModuleScope getScope() {
        return scope;
    }

    public ModuleContext getContext() { return context; }

    void attachRuntime(RuntimeCore runtime) {
        if (context != null) throw new IllegalStateException("Module already attached: " + name);
        scope.attach(runtime.getResourceArbiter(), runtime.getClientSession().getScheduler());
        context = new ModuleContext(this, runtime, scope);
    }

    public int getHudInfoColor() {
        return 0xFFAAAAAA;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }

        if (enabled) {
            enableGeneration++;
            ModuleScope.Activation activation = scope.beginActivation(enableGeneration);
            this.enabled = true;
            try {
                onEnable();
                activation.commit();
            } catch (Throwable failure) {
                this.enabled = false;
                AgentLog.error("Unable to enable module " + name, failure);
            } finally {
                activation.close();
            }
        } else {
            this.enabled = false;
            try {
                onDisable();
            } catch (Throwable failure) {
                AgentLog.error("Unable to disable module " + name, failure);
            } finally {
                scope.reset(ModuleResetReason.DISABLED);
            }
        }
        ConfigManager.saveActiveConfig();
    }

    void forceDisableForUnload() {
        if (!enabled) {
            return;
        }

        enabled = false;
        try {
            onDisable();
        } catch (Throwable failure) {
            AgentLog.error("Unable to unload module " + name, failure);
        } finally {
            scope.reset(ModuleResetReason.UNLOAD);
        }
    }

    void resetScope(ModuleResetReason reason) {
        scope.reset(reason);
        if (enabled) scope.activate(++enableGeneration);
    }

    void cleanupInputScope(ModuleResetReason reason) {
        scope.cleanupInput(reason);
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    public void onClientTick() {
    }

    public void onClientTick(TickEvent.ClientTickEvent event) {
    }

    /** Called once when the active world or local player instance changes. */
    public void onSessionReset() {
    }

    public void onSessionReset(ModuleResetReason reason) {
        onSessionReset();
    }

    /** Called once when gameplay input becomes unavailable due to focus loss or a GUI. */
    public void onInputContextLost() {
    }

    public void onInputContextLost(ModuleResetReason reason) {
        onInputContextLost();
    }

    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
    }

    public void onMouseEvent(MouseEvent event) {
    }

    public void onPlayerJump(LivingEvent.LivingJumpEvent event) {
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
    }

    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
    }

    public int getOutboundPacketDelay(Packet<?> packet) {
        return 0;
    }

    /** True when the packet must remain queued until an explicit flush rather than a deadline. */
    public boolean shouldHoldOutboundPacket(Packet<?> packet) {
        return false;
    }

    /** Explicitly permits a protocol packet to pass an existing lane owned by this module. */
    public boolean shouldBypassOutboundOrdering(Packet<?> packet) {
        return false;
    }

    /** Requests an ordered owner-lane flush before this triggering packet is written. */
    public boolean shouldFlushThenPassOutboundPacket(Packet<?> packet) {
        return false;
    }

    /** Higher values win when several modules request a delay for the same outbound packet. */
    public int getOutboundPacketDelayPriority(Packet<?> packet) {
        return 0;
    }

    public int getInboundPacketDelay(Packet<?> packet) {
        return 0;
    }

    /** True when the packet must remain queued until an explicit flush rather than a deadline. */
    public boolean shouldHoldInboundPacket(Packet<?> packet) {
        return false;
    }

    /** Higher values win when several modules request a delay for the same inbound packet. */
    public int getInboundPacketDelayPriority(Packet<?> packet) {
        return 0;
    }

    public void onOutboundPacket(Packet<?> packet) {
    }

    public void onInboundPacket(Packet<?> packet) {
    }

    public boolean shouldCancelInboundPacket(Packet<?> packet) {
        return false;
    }

    public boolean receivesInboundPacketsWhenDisabled() {
        return false;
    }

    public void onInboundPacketQueued(Packet<?> packet) {
    }

    public void onInboundPacketReleased(Packet<?> packet) {
    }

    /** Runs on the client thread after a delayed inbound packet has been processed. */
    public void onInboundPacketProcessed(Packet<?> packet) {
    }

    public void onPacketDelayOverflow(boolean outbound) {
    }

    public boolean isPacketDelayActive() {
        return false;
    }

    public boolean consumeFlushRequest() {
        return false;
    }

    public boolean isOutboundPacketDelayActive() {
        return isPacketDelayActive();
    }

    public boolean isInboundPacketDelayActive() {
        return isPacketDelayActive();
    }

    public boolean consumeOutboundFlushRequest() {
        return false;
    }

    public boolean consumeInboundFlushRequest() {
        return false;
    }
}
