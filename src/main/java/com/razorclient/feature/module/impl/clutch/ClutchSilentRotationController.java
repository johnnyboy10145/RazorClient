package com.razorclient.feature.module.impl.clutch;

import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.runtime.ModuleScope;
import com.razorclient.runtime.ResourceArbiter;
import java.lang.reflect.Field;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.eventhandler.Event;

/** Owns render-only local model rotations and restores every modified field. */
public final class ClutchSilentRotationController {
    private ResourceArbiter.Lease lease;
    private boolean active;
    private boolean renderSwapActive;
    private EntityPlayerSP renderPlayer;
    private float previousYaw;
    private float yaw;
    private float previousPitch;
    private float pitch;
    private float savedYawHead;
    private float savedPrevYawHead;
    private float savedYawOffset;
    private float savedPrevYawOffset;
    private float savedPitch;
    private float savedPrevPitch;
    private Field renderPlayerField;

    public boolean update(ModuleScope scope, EntityPlayerSP player, float targetYaw, float targetPitch) {
        if (scope == null || player == null) {
            clear();
            return false;
        }
        ClientRotationHelper.get().requestRotations("Clutch", 95, targetYaw, targetPitch);
        if (!acquire(scope)) {
            restoreRenderSwap();
            active = false;
            return false;
        }
        if (active) {
            previousYaw = yaw;
            previousPitch = pitch;
            yaw = ClientRotationHelper.unwrapYaw(targetYaw, yaw);
        } else {
            yaw = ClientRotationHelper.unwrapYaw(targetYaw, player.rotationYawHead);
            previousYaw = ClientRotationHelper.unwrapYaw(player.prevRotationYawHead, yaw);
            previousPitch = player.prevRotationPitch;
        }
        pitch = MathHelper.clamp_float(targetPitch, -90.0F, 90.0F);
        active = true;
        return true;
    }

    public void renderPre(Event event, EntityPlayerSP localPlayer, boolean enabled) {
        restoreRenderSwap();
        if (!enabled || !active || localPlayer == null || lease == null || !lease.isValid()
                || resolvePlayer(event) != localPlayer) return;
        renderPlayer = localPlayer;
        savedYawHead = localPlayer.rotationYawHead;
        savedPrevYawHead = localPlayer.prevRotationYawHead;
        savedYawOffset = localPlayer.renderYawOffset;
        savedPrevYawOffset = localPlayer.prevRenderYawOffset;
        savedPitch = localPlayer.rotationPitch;
        savedPrevPitch = localPlayer.prevRotationPitch;
        localPlayer.rotationYawHead = yaw;
        localPlayer.prevRotationYawHead = previousYaw;
        localPlayer.renderYawOffset = yaw;
        localPlayer.prevRenderYawOffset = previousYaw;
        localPlayer.rotationPitch = pitch;
        localPlayer.prevRotationPitch = previousPitch;
        renderSwapActive = true;
    }

    public void renderPost(Event event, EntityPlayerSP localPlayer) {
        if (renderSwapActive && resolvePlayer(event) == localPlayer) restoreRenderSwap();
    }

    public void restoreRenderSwap() {
        EntityPlayerSP player = renderPlayer;
        if (renderSwapActive && player != null) {
            player.rotationYawHead = savedYawHead;
            player.prevRotationYawHead = savedPrevYawHead;
            player.renderYawOffset = savedYawOffset;
            player.prevRenderYawOffset = savedPrevYawOffset;
            player.rotationPitch = savedPitch;
            player.prevRotationPitch = savedPrevPitch;
        }
        renderSwapActive = false;
        renderPlayer = null;
    }

    public void clear() {
        restoreRenderSwap();
        ResourceArbiter.Lease activeLease = lease;
        lease = null;
        if (activeLease != null && activeLease.isValid()) activeLease.close();
        active = false;
        previousYaw = 0.0F;
        yaw = 0.0F;
        previousPitch = 0.0F;
        pitch = 0.0F;
        ClientRotationHelper helper = ClientRotationHelper.get();
        if ("Clutch".equals(helper.getRequestedOwner())) helper.clearRequestedRotations();
    }

    private boolean acquire(final ModuleScope scope) {
        if (lease != null && lease.renew(2)) return true;
        lease = scope.acquire(ResourceArbiter.Resource.MODEL_ROTATION, 95, 2, new Runnable() {
            @Override public void run() { restoreRenderSwap(); }
        });
        return lease != null;
    }

    private Object resolvePlayer(Event event) {
        if (event == null) return null;
        try {
            if (renderPlayerField == null
                    || !renderPlayerField.getDeclaringClass().isAssignableFrom(event.getClass())) {
                renderPlayerField = event.getClass().getField("entityPlayer");
                renderPlayerField.setAccessible(true);
            }
            return renderPlayerField.get(event);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
