package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;

/** Removes only the local jump cooldown; it does not modify movement packets. */
public final class NoJumpDelayModule extends Module {
    private static final Field JUMP_TICKS_FIELD = resolveJumpTicksField();

    public NoJumpDelayModule() {
        super("No Jump Delay", "Removes Minecraft's local jump cooldown.", Category.MOVEMENT, Keyboard.KEY_NONE);
    }

    @Override
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JUMP_TICKS_FIELD == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }

        try {
            JUMP_TICKS_FIELD.setInt(minecraft.thePlayer, 0);
        } catch (IllegalAccessException ignored) {
            // Reflection is resolved once; a mapping mismatch safely leaves the module inactive.
        }
    }

    private static Field resolveJumpTicksField() {
        try {
            Field field = ReflectionHelper.findField(EntityLivingBase.class, "field_70773_bE", "jumpTicks");
            field.setAccessible(true);
            return field;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public String getHudInfo() {
        return JUMP_TICKS_FIELD == null ? "Unavailable" : "0t";
    }
}
