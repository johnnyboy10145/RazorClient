package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class PlayerEspModule extends Module {
    private static final int[] CHAT_COLORS = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
        0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
        0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.MODERN);
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<RenderMode>("Render Mode", RenderMode.values(), RenderMode.BOTH);
    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 255);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 60);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 60);
    private final BooleanSetting seeInvis = new BooleanSetting("See Invis", false);
    private final BooleanSetting showNames = new BooleanSetting("Show Names", true);
    private final BooleanSetting showHealth = new BooleanSetting("Show Health", true);
    private final BooleanSetting showDistance = new BooleanSetting("Show Distance", true);
    private final BooleanSetting useTeamColors = new BooleanSetting("Use Team Colors", true);
    private final NumberSetting maxDistance = new NumberSetting("Max Distance", 8, 256, 1, 96);
    private final NumberSetting lineWidth = new NumberSetting("Line Width", 1, 5, 1, 2);
    private final NumberSetting fillAlpha = new NumberSetting("Fill Alpha", 0, 100, 1, 12);

    public PlayerEspModule() {
        super("PlayerESP", "Draws boxes and labels around other players through walls.", Category.RENDER, Keyboard.KEY_NONE);
        red.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() == Mode.CLASSIC;
            }
        });
        green.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() == Mode.CLASSIC;
            }
        });
        blue.setVisibility(new java.util.function.BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return mode.getValue() == Mode.CLASSIC;
            }
        });
        addSetting(mode);
        addSetting(renderMode);
        addSetting(red);
        addSetting(green);
        addSetting(blue);
        addSetting(seeInvis);
        addSetting(showNames);
        addSetting(showHealth);
        addSetting(showDistance);
        addSetting(maxDistance);
        addSetting(lineWidth);
        addSetting(fillAlpha);
        addSetting(useTeamColors);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null || minecraft.getRenderManager() == null) {
            return;
        }

        float partialTicks = event == null ? 0.0F : event.partialTicks;
        RenderMode currentRenderMode = renderMode.getValue();
        boolean drawBox = currentRenderMode == RenderMode.BOX || currentRenderMode == RenderMode.BOTH;
        boolean drawOutline = currentRenderMode == RenderMode.OUTLINE || currentRenderMode == RenderMode.BOTH;
        if (!drawBox && !drawOutline && !showNames.isEnabled()) {
            return;
        }

        double maxDistanceSq = maxDistance.getValue() * maxDistance.getValue();
        double viewerX = minecraft.getRenderManager().viewerPosX;
        double viewerY = minecraft.getRenderManager().viewerPosY;
        double viewerZ = minecraft.getRenderManager().viewerPosZ;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GL11.glLineWidth((float) lineWidth.getValue());

            for (Object object : minecraft.theWorld.playerEntities) {
                if (!(object instanceof EntityPlayer)) {
                    continue;
                }

                EntityPlayer player = (EntityPlayer) object;
                if (!shouldRender(minecraft, player, maxDistanceSq)) {
                    continue;
                }

                double x = interpolate(player.lastTickPosX, player.posX, partialTicks) - viewerX;
                double y = interpolate(player.lastTickPosY, player.posY, partialTicks) - viewerY;
                double z = interpolate(player.lastTickPosZ, player.posZ, partialTicks) - viewerZ;

                AxisAlignedBB bb = player.getEntityBoundingBox();
                if (bb == null) {
                    continue;
                }

                AxisAlignedBB renderBox = new AxisAlignedBB(
                    bb.minX - player.posX + x,
                    bb.minY - player.posY + y,
                    bb.minZ - player.posZ + z,
                    bb.maxX - player.posX + x,
                    bb.maxY - player.posY + y,
                    bb.maxZ - player.posZ + z
                ).expand(0.05D, 0.1D, 0.05D);

                float[] colors = getColor(player);
                if (drawBox) {
                    drawFilledBox(renderBox, colors[0], colors[1], colors[2], (float) (fillAlpha.getValue() / 100.0D));
                }
                if (drawOutline) {
                    drawOutlinedBox(renderBox, colors[0], colors[1], colors[2], mode.getValue() == Mode.MODERN ? 0.95F : 1.0F);
                }
            }
        } finally {
            GL11.glLineWidth(1.0F);
            GlStateManager.enableCull();
            GlStateManager.disableLighting();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }

        if (showNames.isEnabled()) {
            renderLabels(minecraft, partialTicks, maxDistanceSq);
        }
    }

    private boolean shouldRender(Minecraft minecraft, EntityPlayer player, double maxDistanceSq) {
        return player != minecraft.thePlayer
            && !player.isDead
            && (seeInvis.isEnabled() || !player.isInvisible())
            && !AntiBotModule.shouldIgnore(player)
            && player.getDistanceSqToEntity(minecraft.thePlayer) <= maxDistanceSq;
    }

    private void renderLabels(Minecraft minecraft, float partialTicks, double maxDistanceSq) {
        FontRenderer font = minecraft.fontRendererObj;
        if (font == null) {
            return;
        }

        double viewerX = minecraft.getRenderManager().viewerPosX;
        double viewerY = minecraft.getRenderManager().viewerPosY;
        double viewerZ = minecraft.getRenderManager().viewerPosZ;

        for (Object object : minecraft.theWorld.playerEntities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }

            EntityPlayer player = (EntityPlayer) object;
            if (!shouldRender(minecraft, player, maxDistanceSq)) {
                continue;
            }

            String label = buildLabel(minecraft, player);
            if (label.isEmpty()) {
                continue;
            }

            double x = interpolate(player.lastTickPosX, player.posX, partialTicks) - viewerX;
            double y = interpolate(player.lastTickPosY, player.posY, partialTicks) - viewerY + player.height + 0.55D;
            double z = interpolate(player.lastTickPosZ, player.posZ, partialTicks) - viewerZ;
            renderLabel(minecraft, font, label, x, y, z, getColor(player));
        }
    }

    private String buildLabel(Minecraft minecraft, EntityPlayer player) {
        StringBuilder builder = new StringBuilder(player.getName());
        if (showHealth.isEnabled()) {
            builder.append(' ').append(Math.round(Math.max(0.0F, player.getHealth()))).append("hp");
        }
        if (showDistance.isEnabled()) {
            builder.append(' ').append(String.format(Locale.US, "%.1fm", player.getDistanceToEntity(minecraft.thePlayer)));
        }
        return builder.toString();
    }

    private void renderLabel(Minecraft minecraft, FontRenderer font, String label, double x, double y, double z, float[] color) {
        float scale = 0.026F;
        int width = font.getStringWidth(label) / 2;
        int textColor = 0xFF000000
            | (((int) (color[0] * 255.0F) & 255) << 16)
            | (((int) (color[1] * 255.0F) & 255) << 8)
            | ((int) (color[2] * 255.0F) & 255);

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        try {
            GlStateManager.translate(x, y, z);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-minecraft.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(minecraft.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
            GlStateManager.scale(-scale, -scale, scale);
            GlStateManager.disableLighting();
            GlStateManager.depthMask(false);
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.enableTexture2D();
            drawLabelBackground(width);
            font.drawString(label, -width, 0, textColor);
        } finally {
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawLabelBackground(int halfWidth) {
        GlStateManager.disableTexture2D();
        GlStateManager.color(0.0F, 0.0F, 0.0F, 0.45F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(-halfWidth - 3, -2);
        GL11.glVertex2f(-halfWidth - 3, 10);
        GL11.glVertex2f(halfWidth + 3, 10);
        GL11.glVertex2f(halfWidth + 3, -2);
        GL11.glEnd();
        GlStateManager.enableTexture2D();
    }

    private void drawOutlinedBox(AxisAlignedBB bb, float r, float g, float b, float a) {
        GlStateManager.color(r, g, b, a);
        GL11.glBegin(GL11.GL_LINES);

        vertex(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ);
        vertex(bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ);
        vertex(bb.maxX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.maxZ);
        vertex(bb.minX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.minZ);

        vertex(bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.minZ);
        vertex(bb.maxX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
        vertex(bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        vertex(bb.minX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.minZ);

        vertex(bb.minX, bb.minY, bb.minZ, bb.minX, bb.maxY, bb.minZ);
        vertex(bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.minZ);
        vertex(bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ);
        vertex(bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);

        GL11.glEnd();
    }

    private void drawFilledBox(AxisAlignedBB bb, float r, float g, float b, float a) {
        GlStateManager.color(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);

        quad(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, bb.minX, bb.maxY, bb.minZ);
        quad(bb.minX, bb.minY, bb.maxZ, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        quad(bb.minX, bb.minY, bb.minZ, bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.minZ);
        quad(bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ, bb.maxX, bb.maxY, bb.minZ);
        quad(bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        quad(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.maxZ);

        GL11.glEnd();
    }

    private void vertex(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private void quad(double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x3, y3, z3);
        GL11.glVertex3d(x4, y4, z4);
    }

    private double interpolate(double previous, double current, float partialTicks) {
        return previous + (current - previous) * partialTicks;
    }

    private float[] getColor(EntityPlayer player) {
        if (useTeamColors.isEnabled()) {
            int teamColor = getTeamColor(player);
            if (teamColor >= 0) {
                return rgb(teamColor);
            }
        }
        return mode.getValue() == Mode.MODERN ? getModernColor(player) : getClassicColor();
    }

    private float[] getClassicColor() {
        return new float[] {
            red.getValue() / 255.0F,
            green.getValue() / 255.0F,
            blue.getValue() / 255.0F
        };
    }

    private float[] getModernColor(EntityPlayer player) {
        double time = System.currentTimeMillis() / 340.0D;
        float wave = (float) ((Math.sin(time + (player.getEntityId() * 0.35D)) + 1.0D) * 0.5D);
        int color = ClickGuiModule.blendColor(ClickGuiModule.getLightAccentColor(), ClickGuiModule.getDarkAccentColor(), wave);
        return rgb(color);
    }

    private float[] rgb(int color) {
        return new float[] {
            ((color >>> 16) & 255) / 255.0F,
            ((color >>> 8) & 255) / 255.0F,
            (color & 255) / 255.0F
        };
    }

    private int getTeamColor(EntityPlayer player) {
        Team team = player.getTeam();
        if (team == null) {
            return -1;
        }
        String formatted = team.formatString(player.getName());
        int marker = formatted.lastIndexOf('\u00A7');
        if (marker < 0 || marker + 1 >= formatted.length()) {
            return -1;
        }
        int index = "0123456789abcdef".indexOf(Character.toLowerCase(formatted.charAt(marker + 1)));
        return index >= 0 ? CHAT_COLORS[index] : -1;
    }

    private enum Mode {
        MODERN("Modern"),
        CLASSIC("Classic");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum RenderMode {
        BOX("Box"),
        OUTLINE("Outline"),
        BOTH("Both");

        private final String label;

        RenderMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
