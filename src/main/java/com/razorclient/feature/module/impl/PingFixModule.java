package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.network.PacketDelayManager;
import org.lwjgl.input.Keyboard;

public final class PingFixModule extends Module {
    private final NumberSetting displayPingOffset = new NumberSetting("Display Ping Offset", -500, 500, 10, 0);

    public PingFixModule() {
        super("Ping Fix", "Displays adjusted local ping info for lag modules.", Category.LAG_MODULES, Keyboard.KEY_NONE);
        addSetting(displayPingOffset);
    }

    @Override
    public String getHudInfo() {
        PacketDelayManager manager = PacketDelayManager.getInstance();
        int queuedDelay = manager == null ? 0 : manager.getApproximateQueuedDelay();
        int shown = Math.max(0, queuedDelay + displayPingOffset.getValue());
        return shown + "ms";
    }

    @Override
    public int getHudInfoColor() {
        RazorClient client = RazorClient.getInstance();
        if (client != null && client.getModuleManager().isPacketDelayActive()) {
            return 0xFFB07CFF;
        }
        return super.getHudInfoColor();
    }
}
