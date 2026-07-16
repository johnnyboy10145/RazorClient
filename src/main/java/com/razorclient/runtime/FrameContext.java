package com.razorclient.runtime;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.entity.RenderManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Immutable camera and projection values captured once for a rendered frame. */
public final class FrameContext {
    private static final ThreadLocal<CaptureState> CAPTURE_STATE = new ThreadLocal<CaptureState>() {
        @Override protected CaptureState initialValue() { return new CaptureState(); }
    };

    private final long sequence;
    private final long nanoTime;
    private final long sessionGeneration;
    private final float partialTicks;
    private final int displayWidth;
    private final int displayHeight;
    private final int scaledWidth;
    private final int scaledHeight;
    private final int scaleFactor;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final boolean guiOpen;
    private final boolean projectionValid;
    private final float[] modelView;
    private final float[] projection;
    private final int[] viewport;

    private FrameContext(long sequence, long nanoTime, long sessionGeneration, float partialTicks, Minecraft minecraft,
            ProjectionBacking backing, boolean projectionValid) {
        this.sequence = sequence;
        this.nanoTime = nanoTime;
        this.sessionGeneration = sessionGeneration;
        this.partialTicks = partialTicks;
        this.displayWidth = minecraft == null ? 0 : minecraft.displayWidth;
        this.displayHeight = minecraft == null ? 0 : minecraft.displayHeight;
        ScaledResolution scaled = minecraft == null ? null : new ScaledResolution(minecraft);
        this.scaledWidth = scaled == null ? 0 : scaled.getScaledWidth();
        this.scaledHeight = scaled == null ? 0 : scaled.getScaledHeight();
        this.scaleFactor = scaled == null ? 1 : scaled.getScaleFactor();
        RenderManager renderManager = minecraft == null ? null : minecraft.getRenderManager();
        this.cameraX = renderManager == null ? 0.0D : renderManager.viewerPosX;
        this.cameraY = renderManager == null ? 0.0D : renderManager.viewerPosY;
        this.cameraZ = renderManager == null ? 0.0D : renderManager.viewerPosZ;
        this.guiOpen = minecraft != null && minecraft.currentScreen != null;
        this.projectionValid = projectionValid;
        this.modelView = backing.modelView;
        this.projection = backing.projection;
        this.viewport = backing.viewport;
    }

    static FrameContext capture(long sequence, long sessionGeneration, float partialTicks, Minecraft minecraft) {
        CaptureState state = CAPTURE_STATE.get();
        ProjectionBacking backing = new ProjectionBacking();
        boolean valid = false;
        try {
            FloatBuffer floats = state.matrixBuffer;
            floats.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, floats);
            floats.rewind();
            floats.get(backing.modelView);
            floats.clear();
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, floats);
            floats.rewind();
            floats.get(backing.projection);
            IntBuffer integers = state.viewportBuffer;
            integers.clear();
            GL11.glGetInteger(GL11.GL_VIEWPORT, integers);
            integers.rewind();
            integers.get(backing.viewport);
            valid = minecraft != null
                && minecraft.displayWidth > 0
                && minecraft.displayHeight > 0
                && backing.viewport[2] > 0
                && backing.viewport[3] > 0
                && isValidMatrix(backing.modelView)
                && isValidMatrix(backing.projection);
        } catch (Throwable ignored) {
            // Forge may deliver a render callback while the display context is being rebuilt.
        }
        return new FrameContext(sequence, System.nanoTime(), sessionGeneration, partialTicks, minecraft, backing, valid);
    }

    private static boolean isValidMatrix(float[] matrix) {
        boolean hasMagnitude = false;
        for (float value : matrix) {
            if (!Float.isFinite(value)) return false;
            hasMagnitude |= Math.abs(value) > 1.0E-12F;
        }
        return hasMagnitude;
    }

    public long getSequence() { return sequence; }
    public long getNanoTime() { return nanoTime; }
    public long getSessionGeneration() { return sessionGeneration; }
    public float getPartialTicks() { return partialTicks; }
    public int getDisplayWidth() { return displayWidth; }
    public int getDisplayHeight() { return displayHeight; }
    public int getScaledWidth() { return scaledWidth; }
    public int getScaledHeight() { return scaledHeight; }
    public int getScaleFactor() { return scaleFactor; }
    public double getCameraX() { return cameraX; }
    public double getCameraY() { return cameraY; }
    public double getCameraZ() { return cameraZ; }
    public boolean isGuiOpen() { return guiOpen; }
    public boolean isProjectionValid() { return projectionValid; }
    public float getModelView(int index) { return modelView[index]; }
    public float getProjection(int index) { return projection[index]; }
    public int getViewport(int index) { return viewport[index]; }

    private static final class CaptureState {
        private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
        private final IntBuffer viewportBuffer = BufferUtils.createIntBuffer(4);
    }

    private static final class ProjectionBacking {
        private final float[] modelView = new float[16];
        private final float[] projection = new float[16];
        private final int[] viewport = new int[4];
    }
}
