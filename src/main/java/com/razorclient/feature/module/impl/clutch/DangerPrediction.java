package com.razorclient.feature.module.impl.clutch;

import net.minecraft.util.AxisAlignedBB;

/** Immutable fall prediction shared by activation and candidate selection. */
public final class DangerPrediction {
    private static final DangerPrediction SAFE = new DangerPrediction(false, 0.0D, 0.0D, 0.0D,
        null, 0, 0);

    private final boolean dangerous;
    private final double projectedX;
    private final double projectedY;
    private final double projectedZ;
    private final AxisAlignedBB projectedBounds;
    private final int ticksAhead;
    private final int unsupportedDepth;

    private DangerPrediction(boolean dangerous, double projectedX, double projectedY,
            double projectedZ, AxisAlignedBB projectedBounds, int ticksAhead,
            int unsupportedDepth) {
        this.dangerous = dangerous;
        this.projectedX = projectedX;
        this.projectedY = projectedY;
        this.projectedZ = projectedZ;
        this.projectedBounds = projectedBounds;
        this.ticksAhead = ticksAhead;
        this.unsupportedDepth = unsupportedDepth;
    }

    public static DangerPrediction safe() {
        return SAFE;
    }

    public static DangerPrediction dangerous(double projectedX, double projectedY,
            double projectedZ, AxisAlignedBB projectedBounds, int ticksAhead,
            int unsupportedDepth) {
        return new DangerPrediction(true, projectedX, projectedY, projectedZ, projectedBounds,
            Math.max(1, ticksAhead), Math.max(1, unsupportedDepth));
    }

    public boolean isDangerous() { return dangerous; }
    public double getProjectedX() { return projectedX; }
    public double getProjectedY() { return projectedY; }
    public double getProjectedZ() { return projectedZ; }
    public AxisAlignedBB getProjectedBounds() { return projectedBounds; }
    public int getTicksAhead() { return ticksAhead; }
    public int getUnsupportedDepth() { return unsupportedDepth; }
}
