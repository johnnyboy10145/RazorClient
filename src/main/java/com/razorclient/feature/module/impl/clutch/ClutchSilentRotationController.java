package com.razorclient.feature.module.impl.clutch;

import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.runtime.ModuleScope;
import net.minecraft.client.entity.EntityPlayerSP;

/** Publishes Clutch rotations; shared model rendering is owned by ClientRotationHelper. */
public final class ClutchSilentRotationController {
    private boolean active;

    public boolean update(ModuleScope scope, EntityPlayerSP player, float targetYaw, float targetPitch) {
        if (scope == null || player == null
                || !ClientRotationHelper.get().requestRotations("Clutch", 95, targetYaw, targetPitch)) {
            active = false;
            return false;
        }
        active = true;
        return true;
    }

    public void clear() {
        if (active) ClientRotationHelper.get().clearRequestedRotations("Clutch");
        active = false;
    }
}
