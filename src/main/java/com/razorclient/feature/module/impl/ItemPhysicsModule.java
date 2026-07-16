package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.runtime.EntityFrame;
import com.razorclient.runtime.EntityRecord;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityItem;
import org.lwjgl.input.Keyboard;

public final class ItemPhysicsModule extends Module {
    private final BooleanSetting noBob = new BooleanSetting("No Bob", true);
    private final BooleanSetting noSpin = new BooleanSetting("No Spin", true);
    private final Map<EntityItem, Float> originalPhases = new IdentityHashMap<EntityItem, Float>();

    public ItemPhysicsModule() {
        super("Item Physics", "Stabilizes dropped items; 1.8.9 shares one phase for bob and spin.", Category.RENDER, Keyboard.KEY_NONE);
        addSetting(noBob);
        addSetting(noSpin);
    }

    @Override
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        RazorClient client = RazorClient.getInstance();
        if (client == null || minecraft.theWorld == null || (!noBob.isEnabled() && !noSpin.isEnabled())) {
            restoreAll();
            return;
        }

        EntityFrame frame = client.getModuleManager().getEntityFrame();
        for (int index = 0; index < frame.size(); index++) {
            EntityRecord record = frame.get(index);
            if (record == null || !record.isDroppedItem()) continue;
            net.minecraft.entity.Entity entity = minecraft.theWorld.getEntityByID(record.getEntityId());
            if (!(entity instanceof EntityItem)) continue;
            EntityItem item = (EntityItem) entity;
            if (!originalPhases.containsKey(item)) originalPhases.put(item, Float.valueOf(item.hoverStart));
            // Minecraft uses /10 for bob and /20 for spin; cancel the selected phase locally.
            item.hoverStart = noBob.isEnabled() ? -(item.age / 10.0F) : -(item.age / 20.0F);
        }
        pruneDeadItems();
    }

    @Override
    protected void onDisable() {
        restoreAll();
    }

    @Override
    public void onSessionReset() {
        restoreAll();
    }

    @Override
    public String getHudInfo() {
        return noBob.isEnabled() && noSpin.isEnabled() ? "No Bob*" : noSpin.isEnabled() ? "No Spin" : "No Bob";
    }

    private void pruneDeadItems() {
        Iterator<Map.Entry<EntityItem, Float>> iterator = originalPhases.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<EntityItem, Float> entry = iterator.next();
            if (entry.getKey().isDead) iterator.remove();
        }
    }

    private void restoreAll() {
        for (Map.Entry<EntityItem, Float> entry : originalPhases.entrySet()) {
            EntityItem item = entry.getKey();
            if (item != null && !item.isDead) item.hoverStart = entry.getValue().floatValue();
        }
        originalPhases.clear();
    }
}
