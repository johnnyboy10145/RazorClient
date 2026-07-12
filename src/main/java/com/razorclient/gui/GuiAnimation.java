package com.razorclient.gui;

public final class GuiAnimation {
    private static final long DEFAULT_DURATION_MS = 140L;

    private float value;
    private float target;
    private long lastUpdate;

    public GuiAnimation() {
        this.value = 0.0F;
        this.target = 0.0F;
        this.lastUpdate = System.nanoTime();
    }

    public float update(boolean active) {
        return update(active ? 1.0F : 0.0F, DEFAULT_DURATION_MS);
    }

    public float update(float target, long durationMs) {
        long now = System.nanoTime();
        long elapsedNs = Math.max(0L, now - lastUpdate);
        lastUpdate = now;
        this.target = Math.max(0.0F, Math.min(1.0F, target));

        float duration = Math.max(1.0F, durationMs);
        float step = Math.min(1.0F, elapsedNs / (duration * 1000000.0F));
        value += (this.target - value) * GuiTheme.easeOutCubic(step);
        if (Math.abs(this.target - value) < 0.002F) {
            value = this.target;
        }
        return value;
    }

    public float get() {
        return value;
    }
}
