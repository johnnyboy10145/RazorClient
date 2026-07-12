package com.razorclient.inject;

import com.razorclient.event.PrePlayerInputEvent;
import java.lang.reflect.Field;
import net.minecraftforge.common.MinecraftForge;

final class LunarAccess {
    static void rewriteMovementInput(Object input, PrePlayerInputEvent unused) {
        try {
            Field forward = field(input, "moveForward");
            Field strafe = field(input, "moveStrafe");
            Field jump = field(input, "jump");
            Field sneak = field(input, "sneak");
            PrePlayerInputEvent event = new PrePlayerInputEvent(forward.getFloat(input), strafe.getFloat(input), jump.getBoolean(input), sneak.getBoolean(input));
            MinecraftForge.EVENT_BUS.post(event);
            forward.setFloat(input, event.getForward());
            strafe.setFloat(input, event.getStrafe());
            jump.setBoolean(input, event.isJump());
            sneak.setBoolean(input, event.isSneak());
        } catch (ReflectiveOperationException failure) {
            AgentLog.error("Movement input adapter failed", failure);
        }
    }

    private static Field field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getField(name);
        field.setAccessible(true);
        return field;
    }

    private LunarAccess() {}
}
