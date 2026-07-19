package com.razorclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;

public class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();
    private Minecraft mc = Minecraft.getMinecraft();

    private float targetYaw, targetPitch;
    private float currentYaw, currentPitch;
    private boolean rotating;

    private RotationManager() {}

    public static RotationManager getInstance() { return INSTANCE; }

    public void setRotations(float yaw, float pitch, boolean instant) {
        this.targetYaw = yaw;
        this.targetPitch = pitch;
        if (instant) {
            this.currentYaw = yaw;
            this.currentPitch = pitch;
        }
        this.rotating = true;
    }

    public void update() {
        if (!rotating || mc.thePlayer == null) return;

        float speed = getRotationSpeed();
        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;

        if (Math.abs(yawDiff) < 0.1f && Math.abs(pitchDiff) < 0.1f) {
            currentYaw = targetYaw;
            currentPitch = targetPitch;
            rotating = false;
            return;
        }

        currentYaw += MathHelper.clamp_float(yawDiff, -speed, speed);
        currentPitch += MathHelper.clamp_float(pitchDiff, -speed * 0.6f, speed * 0.6f);

        mc.thePlayer.rotationYaw = currentYaw;
        mc.thePlayer.rotationPitch = currentPitch;
    }

    private float getRotationSpeed() {
        float base = 8f + (float)(Math.random() * 6f);
        float sine = (float)Math.sin(System.currentTimeMillis() / 1000.0) * 1.2f;
        return Math.max(3f, base + sine);
    }

    public boolean isRotating() { return rotating; }
    public float getYaw() { return currentYaw; }
    public float getPitch() { return currentPitch; }

    public void reset() {
        rotating = false;
        if (mc.thePlayer != null) {
            currentYaw = mc.thePlayer.rotationYaw;
            currentPitch = mc.thePlayer.rotationPitch;
        }
    }
}