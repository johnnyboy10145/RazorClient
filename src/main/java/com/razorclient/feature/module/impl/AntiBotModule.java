package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.runtime.EntitySnapshotService.EntitySnapshot;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.input.Keyboard;

public final class AntiBotModule extends Module {
    private final BooleanSetting requireTabList = new BooleanSetting("Require Tab List", true);
    private final BooleanSetting ignoreSpectators = new BooleanSetting("Ignore Spectators", true);

    public AntiBotModule() {
        super("AntiBot", "Filters NPCs from players.", Category.CLIENT, Keyboard.KEY_NONE);
        addSetting(requireTabList);
        addSetting(ignoreSpectators);
    }

    public static boolean shouldIgnore(EntityPlayer player) {
        RazorClient client = RazorClient.getInstance();
        if (client == null) {
            return false;
        }

        AntiBotModule antiBot = client.getModuleManager().getModule(AntiBotModule.class);
        return antiBot != null && antiBot.isEnabled() && antiBot.isBot(player);
    }

    public boolean isBot(EntityPlayer player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (player == null || minecraft.thePlayer == null || minecraft.theWorld == null) {
            return false;
        }

        if (player == minecraft.thePlayer) {
            return false;
        }

        if (ignoreSpectators.isEnabled() && player.isSpectator()) {
            return true;
        }
        RazorClient client = RazorClient.getInstance();
        EntitySnapshot snapshot = client == null ? null
            : client.getModuleManager().getEntitySnapshots().find(player.getEntityId());
        if (snapshot != null && snapshot.getEntity() == player) {
            if (ignoreSpectators.isEnabled() && snapshot.isSpectator()) return true;
            return requireTabList.isEnabled() && !snapshot.isInTabList();
        }
        return requireTabList.isEnabled() && !isInTabList(minecraft, player);
    }

    private boolean isInTabList(Minecraft minecraft, EntityPlayer player) {
        Collection<NetworkPlayerInfo> playerInfoMap = minecraft.getNetHandler() == null
            ? null
            : minecraft.getNetHandler().getPlayerInfoMap();
        if (playerInfoMap == null || playerInfoMap.isEmpty()) {
            return true;
        }

        for (NetworkPlayerInfo playerInfo : playerInfoMap) {
            if (playerInfo == null || playerInfo.getGameProfile() == null) {
                continue;
            }

            if (player.getUniqueID().equals(playerInfo.getGameProfile().getId())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String getHudInfo() {
        return requireTabList.isEnabled() ? "Tab" : "Local";
    }
}
