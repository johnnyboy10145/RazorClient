package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.combat.CombatTargetService;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import com.razorclient.runtime.EntityFrame;
import com.razorclient.runtime.EntityRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Team;
import com.razorclient.runtime.FrameContext;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

public final class PlayerEspModule extends Module {
    private static final int[] CHAT_COLORS = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final int MAX_SNAPSHOTS = 256;

    private final EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.MODERN);
    private final EnumSetting<RenderMode> renderMode = new EnumSetting<RenderMode>("Render Mode", RenderMode.values(), RenderMode.BOTH);
    private final EnumSetting<ProjectionMode> projectionMode = new EnumSetting<ProjectionMode>("Projection Mode", ProjectionMode.values(), ProjectionMode.BOTH);
    private final EnumSetting<TargetType> targetType = new EnumSetting<TargetType>("Target Type", TargetType.values(), TargetType.BOTH);
    private final NumberSetting red = new NumberSetting("Red", 0, 255, 5, 45);
    private final NumberSetting green = new NumberSetting("Green", 0, 255, 5, 210);
    private final NumberSetting blue = new NumberSetting("Blue", 0, 255, 5, 110);
    private final NumberSetting hiddenRed = new NumberSetting("Hidden Red", 0, 255, 5, 235);
    private final NumberSetting hiddenGreen = new NumberSetting("Hidden Green", 0, 255, 5, 70);
    private final NumberSetting hiddenBlue = new NumberSetting("Hidden Blue", 0, 255, 5, 70);
    private final NumberSetting targetRed = new NumberSetting("Target Red", 0, 255, 5, 255);
    private final NumberSetting targetGreen = new NumberSetting("Target Green", 0, 255, 5, 190);
    private final NumberSetting targetBlue = new NumberSetting("Target Blue", 0, 255, 5, 70);
    private final BooleanSetting seeInvis = new BooleanSetting("See Invis", false);
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true);
    private final BooleanSetting showNames = new BooleanSetting("Show Names", true);
    private final BooleanSetting showHealth = new BooleanSetting("Show Health", true);
    private final BooleanSetting healthBar = new BooleanSetting("Health Bar", true);
    private final BooleanSetting healthValue = new BooleanSetting("Health Value", true);
    private final BooleanSetting showDistance = new BooleanSetting("Show Distance", true);
    private final BooleanSetting showArmor = new BooleanSetting("Armor", true);
    private final BooleanSetting showHeldItem = new BooleanSetting("Held Item", true);
    private final BooleanSetting tracers = new BooleanSetting("Tracers", false);
    private final BooleanSetting targetHighlight = new BooleanSetting("Target Highlight", true);
    private final BooleanSetting useTeamColors = new BooleanSetting("Use Team Colors", true);
    private final NumberSetting maxDistance = new NumberSetting("Max Distance", 8, 256, 1, 96);
    private final NumberSetting lineWidth = new NumberSetting("Line Width", 1, 5, 1, 2);
    private final NumberSetting fillAlpha = new NumberSetting("Fill Alpha", 0, 100, 1, 12);

    private final FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer projectedPoint = BufferUtils.createFloatBuffer(3);
    private final EntityRecord[] renderSnapshots = new EntityRecord[MAX_SNAPSHOTS];
    private final int[] renderTeamColors = new int[MAX_SNAPSHOTS];
    private final ProjectedSnapshot[] projected = new ProjectedSnapshot[MAX_SNAPSHOTS];
    private volatile EntityFrame snapshots = EntityFrame.EMPTY;
    private int renderSnapshotCount;
    private int projectedCount;
    private int activeTargetId = -1;
    private volatile int renderedCount;
    private long animationNanos;

    public PlayerEspModule() {
        super("PlayerESP", "Local-only 2D and 3D entity overlay.", Category.RENDER, Keyboard.KEY_NONE);
        java.util.function.BooleanSupplier customColors = () -> mode.getValue() == Mode.CLASSIC;
        red.setVisibility(customColors);
        green.setVisibility(customColors);
        blue.setVisibility(customColors);
        healthBar.setVisibility(() -> showHealth.isEnabled());
        healthValue.setVisibility(() -> showHealth.isEnabled());
        addSetting(mode); addSetting(renderMode); addSetting(projectionMode); addSetting(targetType);
        addSetting(red); addSetting(green); addSetting(blue);
        addSetting(hiddenRed); addSetting(hiddenGreen); addSetting(hiddenBlue);
        addSetting(targetRed); addSetting(targetGreen); addSetting(targetBlue);
        addSetting(seeInvis); addSetting(throughWalls); addSetting(showNames); addSetting(showHealth);
        addSetting(healthBar); addSetting(healthValue); addSetting(showDistance); addSetting(showArmor);
        addSetting(showHeldItem); addSetting(tracers); addSetting(targetHighlight); addSetting(maxDistance);
        addSetting(lineWidth); addSetting(fillAlpha); addSetting(useTeamColors);
    }

    @Override
    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        RazorClient client = RazorClient.getInstance();
        if (client == null || mc.theWorld == null || mc.thePlayer == null) {
            clearRenderState();
            return;
        }
        snapshots = client.getModuleManager().getEntityFrame();
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.getRenderManager() == null) return;
        prepareRenderSnapshots(mc);
        projectedCount = 0;
        RazorClient client = RazorClient.getInstance();
        FrameContext frame = client == null ? null : client.getModuleManager().getFrameContext();
        if (projectionMode.getValue().draws3d()) render3d(mc, frame, event.partialTicks);
        if (projectionMode.getValue().draws2d()) project2d(mc, frame, event.partialTicks);
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!projectionMode.getValue().draws2d() || projectedCount == 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.fontRendererObj == null) return;
        render2d(mc, event.resolution);
    }

    @Override protected void onDisable() { clearRenderState(); }
    @Override public void onSessionReset() { clearRenderState(); }
    @Override public void onInputContextLost() { projectedCount = 0; }

    private boolean isEligible(Minecraft mc, EntityRecord snapshot, double limit) {
        if (snapshot == null || snapshot.isDead()) return false;
        boolean player = snapshot.isPlayer();
        Entity entity = mc.theWorld.getEntityByID(snapshot.getEntityId());
        boolean mob = entity instanceof EntityMob || entity instanceof EntityAnimal;
        if ((player && !targetType.getValue().players) || (mob && !targetType.getValue().mobs) || (!player && !mob)) return false;
        if (snapshot.getDistanceToHitbox() > limit || (!throughWalls.isEnabled() && !snapshot.isVisible())) return false;
        return entity instanceof EntityLivingBase
            && CombatTargetService.isValid(mc, (EntityLivingBase) entity, player, mob,
                seeInvis.isEnabled(), false, false, limit);
    }

    private void prepareRenderSnapshots(Minecraft mc) {
        renderSnapshotCount = 0;
        EntityFrame frame = snapshots;
        if (frame == null) {
            renderedCount = 0;
            return;
        }
        activeTargetId = getContext().getTargetPublications().activeTarget(getContext().getTick());
        double limit = maxDistance.getValue();
        for (int i = 0; i < frame.size() && renderSnapshotCount < MAX_SNAPSHOTS; i++) {
            EntityRecord snapshot = frame.get(i);
            if (!isEligible(mc, snapshot, limit)) continue;
            renderSnapshots[renderSnapshotCount] = snapshot;
            Entity entity = mc.theWorld.getEntityByID(snapshot.getEntityId());
            renderTeamColors[renderSnapshotCount] = entity instanceof EntityPlayer
                ? getTeamColor((EntityPlayer) entity) : -1;
            renderSnapshotCount++;
        }
        animationNanos = System.nanoTime();
        renderedCount = renderSnapshotCount;
    }

    private void render3d(Minecraft mc, FrameContext frame, float partialTicks) {
        double viewX = frame == null ? mc.getRenderManager().viewerPosX : frame.getCameraX();
        double viewY = frame == null ? mc.getRenderManager().viewerPosY : frame.getCameraY();
        double viewZ = frame == null ? mc.getRenderManager().viewerPosZ : frame.getCameraZ();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GlStateManager.disableTexture2D(); GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableLighting(); GlStateManager.disableCull();
            if (throughWalls.isEnabled()) { GlStateManager.disableDepth(); GlStateManager.depthMask(false); }
            GL11.glLineWidth(lineWidth.getValue());
            for (int index = 0; index < renderSnapshotCount; index++) {
                EntityRecord snapshot = renderSnapshots[index];
                int color = colorFor(snapshot, renderTeamColors[index]);
                float r = ((color >>> 16) & 255) / 255.0F;
                float g = ((color >>> 8) & 255) / 255.0F;
                float b = (color & 255) / 255.0F;
                double offsetX = snapshot.interpolateX(partialTicks) - snapshot.getX();
                double offsetY = snapshot.interpolateY(partialTicks) - snapshot.getY();
                double offsetZ = snapshot.interpolateZ(partialTicks) - snapshot.getZ();
                double minX = snapshot.getMinX() + offsetX - viewX - 0.04D;
                double minY = snapshot.getMinY() + offsetY - viewY - 0.08D;
                double minZ = snapshot.getMinZ() + offsetZ - viewZ - 0.04D;
                double maxX = snapshot.getMaxX() + offsetX - viewX + 0.04D;
                double maxY = snapshot.getMaxY() + offsetY - viewY + 0.08D;
                double maxZ = snapshot.getMaxZ() + offsetZ - viewZ + 0.04D;
                if (renderMode.getValue().fill) drawFilledBox(minX, minY, minZ, maxX, maxY, maxZ, r, g, b, fillAlpha.getValue() / 100.0F);
                if (renderMode.getValue().outline) drawOutlinedBox(minX, minY, minZ, maxX, maxY, maxZ, r, g, b, 1.0F);
            }
        } finally {
            GlStateManager.depthMask(true); GlStateManager.enableDepth(); GlStateManager.enableCull();
            GlStateManager.enableTexture2D(); GlStateManager.disableBlend();
            GlStateManager.color(1, 1, 1, 1); GL11.glLineWidth(1.0F);
            GL11.glPopMatrix(); GL11.glPopAttrib();
        }
    }

    private void project2d(Minecraft mc, FrameContext frame, float partialTicks) {
        if (frame == null || !frame.isProjectionValid()) {
            projectedCount = 0;
            return;
        }

        modelView.clear(); projection.clear(); viewport.clear();
        for (int i = 0; i < 16; i++) modelView.put(frame.getModelView(i));
        for (int i = 0; i < 16; i++) projection.put(frame.getProjection(i));
        for (int i = 0; i < 4; i++) viewport.put(frame.getViewport(i));
        modelView.flip(); projection.flip(); viewport.flip();
        int scaledWidth = frame.getScaledWidth();
        int scaledHeight = frame.getScaledHeight();
        double viewerX = frame.getCameraX();
        double viewerY = frame.getCameraY();
        double viewerZ = frame.getCameraZ();
        for (int index = 0; index < renderSnapshotCount; index++) {
            EntityRecord snapshot = renderSnapshots[index];
            double offsetX = snapshot.interpolateX(partialTicks) - snapshot.getX();
            double offsetY = snapshot.interpolateY(partialTicks) - snapshot.getY();
            double offsetZ = snapshot.interpolateZ(partialTicks) - snapshot.getZ();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            boolean any = false;
            for (int corner = 0; corner < 8; corner++) {
                double x = ((corner & 1) == 0 ? snapshot.getMinX() : snapshot.getMaxX()) + offsetX - viewerX;
                double y = ((corner & 2) == 0 ? snapshot.getMinY() : snapshot.getMaxY()) + offsetY - viewerY;
                double z = ((corner & 4) == 0 ? snapshot.getMinZ() : snapshot.getMaxZ()) + offsetZ - viewerZ;
                projectedPoint.clear();
                if (!GLU.gluProject((float) x, (float) y, (float) z, modelView, projection, viewport, projectedPoint)) continue;
                float depth = projectedPoint.get(2);
                if (depth < 0.0F || depth > 1.0F) continue;
                float sx = projectedPoint.get(0) * scaledWidth / Math.max(1.0F, mc.displayWidth);
                float sy = (mc.displayHeight - projectedPoint.get(1)) * scaledHeight / Math.max(1.0F, mc.displayHeight);
                minX = Math.min(minX, sx); minY = Math.min(minY, sy); maxX = Math.max(maxX, sx); maxY = Math.max(maxY, sy); any = true;
            }
            if (any && maxX >= 0 && maxY >= 0 && minX <= scaledWidth && minY <= scaledHeight) {
                ProjectedSnapshot output = projected[projectedCount];
                if (output == null) projected[projectedCount] = output = new ProjectedSnapshot();
                output.set(snapshot, renderTeamColors[index], minX, minY, maxX, maxY);
                projectedCount++;
            }
        }
    }

    private void render2d(Minecraft mc, ScaledResolution resolution) {
        FontRenderer font = mc.fontRendererObj;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GlStateManager.enableBlend(); GlStateManager.disableDepth(); GlStateManager.depthMask(false);
            for (int index = 0; index < projectedCount; index++) {
                ProjectedSnapshot projection = projected[index];
                EntityRecord snapshot = projection.snapshot;
                int color = 0xFF000000 | colorFor(snapshot, projection.teamColor);
                int translucent = (fillAlpha.getValue() * 255 / 100 << 24) | (color & 0xFFFFFF);
                int x1 = Math.round(projection.minX), y1 = Math.round(projection.minY);
                int x2 = Math.round(projection.maxX), y2 = Math.round(projection.maxY);
                if (renderMode.getValue().fill) Gui.drawRect(x1, y1, x2, y2, translucent);
                if (renderMode.getValue().outline) drawScreenBox(x1, y1, x2, y2, color);
                if (showHealth.isEnabled() && healthBar.isEnabled()) drawHealthBar(snapshot, x1, y1, y2);
                drawInformation(font, snapshot, x1, y1, x2, y2, color);
                if (tracers.isEnabled()) drawTracer(resolution, x1 + (x2 - x1) / 2, y2, color);
            }
        } finally {
            GlStateManager.depthMask(true); GlStateManager.enableDepth(); GlStateManager.disableBlend();
            GlStateManager.enableTexture2D(); GlStateManager.color(1, 1, 1, 1);
            GL11.glLineWidth(1.0F); GL11.glPopMatrix(); GL11.glPopAttrib();
        }
    }

    private void drawInformation(FontRenderer font, EntityRecord s, int x1, int y1, int x2, int y2, int color) {
        StringBuilder top = new StringBuilder();
        if (showNames.isEnabled()) top.append(s.getName());
        if (showHealth.isEnabled() && healthValue.isEnabled()) append(top, Math.round(s.getHealth()) + "hp");
        if (showDistance.isEnabled()) append(top, formatDistance(s.getDistanceToHitbox()));
        if (top.length() > 0) {
            String value = top.toString();
            font.drawStringWithShadow(value, (x1 + x2 - font.getStringWidth(value)) / 2.0F, y1 - 10, color);
        }
        StringBuilder bottom = new StringBuilder();
        if (showArmor.isEnabled()) append(bottom, "Armor " + s.getArmor());
        if (showHeldItem.isEnabled() && !s.getHeldItemName().isEmpty()) append(bottom, s.getHeldItemName());
        if (bottom.length() > 0) {
            String value = bottom.toString();
            font.drawStringWithShadow(value, (x1 + x2 - font.getStringWidth(value)) / 2.0F, y2 + 2, 0xFFFFFFFF);
        }
    }

    private static String formatDistance(double distance) {
        long tenths = Math.max(0L, Math.round(distance * 10.0D));
        return (tenths / 10L) + "." + (tenths % 10L) + "m";
    }

    private void append(StringBuilder builder, String value) { if (builder.length() > 0) builder.append(" | "); builder.append(value); }
    private void drawHealthBar(EntityRecord s, int x, int top, int bottom) {
        float ratio = Math.max(0.0F, Math.min(1.0F, s.getHealth() / Math.max(1.0F, s.getMaxHealth())));
        int filled = Math.round((bottom - top) * ratio);
        int healthColor = ratio > 0.5F ? 0xFF42D66A : ratio > 0.25F ? 0xFFFFB340 : 0xFFFF4B4B;
        Gui.drawRect(x - 4, top - 1, x - 2, bottom + 1, 0xAA000000);
        Gui.drawRect(x - 3, bottom - filled, x - 2, bottom, healthColor);
    }
    private void drawScreenBox(int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y1 + lineWidth.getValue(), color); Gui.drawRect(x1, y2 - lineWidth.getValue(), x2, y2, color);
        Gui.drawRect(x1, y1, x1 + lineWidth.getValue(), y2, color); Gui.drawRect(x2 - lineWidth.getValue(), y1, x2, y2, color);
    }
    private void drawTracer(ScaledResolution resolution, int x, int y, int color) {
        float r = ((color >>> 16) & 255) / 255.0F, g = ((color >>> 8) & 255) / 255.0F, b = (color & 255) / 255.0F;
        GlStateManager.disableTexture2D(); GL11.glLineWidth(lineWidth.getValue()); GL11.glColor4f(r, g, b, 0.9F);
        GL11.glBegin(GL11.GL_LINES); GL11.glVertex2f(resolution.getScaledWidth() / 2.0F, resolution.getScaledHeight()); GL11.glVertex2f(x, y); GL11.glEnd();
        GlStateManager.enableTexture2D();
    }

    private int colorFor(EntityRecord snapshot, int teamColor) {
        if (targetHighlight.isEnabled() && snapshot.getEntityId() == activeTargetId) return rgb(targetRed, targetGreen, targetBlue);
        if (!snapshot.isVisible()) return rgb(hiddenRed, hiddenGreen, hiddenBlue);
        if (useTeamColors.isEnabled() && teamColor >= 0) return teamColor;
        if (mode.getValue() == Mode.MODERN) {
            float wave = (float) ((Math.sin(animationNanos / 340000000.0D
                + snapshot.getEntityId() * 0.35D) + 1.0D) * 0.5D);
            return ClickGuiModule.blendColor(ClickGuiModule.getLightAccentColor(), ClickGuiModule.getDarkAccentColor(), wave);
        }
        return rgb(red, green, blue);
    }
    private int rgb(NumberSetting r, NumberSetting g, NumberSetting b) { return r.getValue() << 16 | g.getValue() << 8 | b.getValue(); }

    private int getTeamColor(EntityPlayer player) {
        Team team = player.getTeam(); if (team == null) return -1;
        String formatted = team.formatString(player.getName()); int marker = formatted.lastIndexOf('\u00A7');
        if (marker < 0 || marker + 1 >= formatted.length()) return -1;
        int index = "0123456789abcdef".indexOf(Character.toLowerCase(formatted.charAt(marker + 1)));
        return index >= 0 ? CHAT_COLORS[index] : -1;
    }

    private void clearRenderState() {
        snapshots = EntityFrame.EMPTY;
        renderSnapshotCount = 0;
        projectedCount = 0;
        renderedCount = 0;
        activeTargetId = -1;
    }
    @Override public String getHudInfo() { return projectionMode.getValue() + " " + renderedCount; }

    private void drawOutlinedBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            float r, float g, float blue, float a) {
        GlStateManager.color(r, g, blue, a); GL11.glBegin(GL11.GL_LINES);
        edge(minX,minY,minZ,maxX,minY,minZ); edge(maxX,minY,minZ,maxX,minY,maxZ); edge(maxX,minY,maxZ,minX,minY,maxZ); edge(minX,minY,maxZ,minX,minY,minZ);
        edge(minX,maxY,minZ,maxX,maxY,minZ); edge(maxX,maxY,minZ,maxX,maxY,maxZ); edge(maxX,maxY,maxZ,minX,maxY,maxZ); edge(minX,maxY,maxZ,minX,maxY,minZ);
        edge(minX,minY,minZ,minX,maxY,minZ); edge(maxX,minY,minZ,maxX,maxY,minZ); edge(maxX,minY,maxZ,maxX,maxY,maxZ); edge(minX,minY,maxZ,minX,maxY,maxZ); GL11.glEnd();
    }
    private void edge(double a,double b,double c,double d,double e,double f){GL11.glVertex3d(a,b,c);GL11.glVertex3d(d,e,f);}
    private void drawFilledBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            float r,float g,float blue,float a){
        GlStateManager.color(r,g,blue,a); GL11.glBegin(GL11.GL_QUADS);
        quad(minX,minY,minZ,maxX,minY,minZ,maxX,maxY,minZ,minX,maxY,minZ); quad(minX,minY,maxZ,maxX,minY,maxZ,maxX,maxY,maxZ,minX,maxY,maxZ);
        quad(minX,minY,minZ,minX,minY,maxZ,minX,maxY,maxZ,minX,maxY,minZ); quad(maxX,minY,minZ,maxX,minY,maxZ,maxX,maxY,maxZ,maxX,maxY,minZ);
        quad(minX,minY,minZ,maxX,minY,minZ,maxX,minY,maxZ,minX,minY,maxZ); quad(minX,maxY,minZ,maxX,maxY,minZ,maxX,maxY,maxZ,minX,maxY,maxZ); GL11.glEnd();
    }
    private void quad(double a,double b,double c,double d,double e,double f,double g,double h,double i,double j,double k,double l){GL11.glVertex3d(a,b,c);GL11.glVertex3d(d,e,f);GL11.glVertex3d(g,h,i);GL11.glVertex3d(j,k,l);}

    private static final class ProjectedSnapshot {
        private EntityRecord snapshot;
        private int teamColor;
        private float minX;
        private float minY;
        private float maxX;
        private float maxY;

        private void set(EntityRecord snapshot, int teamColor, float minX, float minY, float maxX, float maxY) {
            this.snapshot = snapshot;
            this.teamColor = teamColor;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }
    private enum Mode { MODERN("Modern"), CLASSIC("Classic"); final String label; Mode(String l){label=l;} @Override public String toString(){return label;} }
    private enum RenderMode { BOX("Box",true,false), OUTLINE("Outline",false,true), BOTH("Both",true,true); final String label; final boolean fill,outline; RenderMode(String l,boolean f,boolean o){label=l;fill=f;outline=o;} @Override public String toString(){return label;} }
    private enum ProjectionMode { TWO_D("2D",true,false), THREE_D("3D",false,true), BOTH("Both",true,true); final String label; final boolean d2,d3; ProjectionMode(String l,boolean a,boolean b){label=l;d2=a;d3=b;} boolean draws2d(){return d2;} boolean draws3d(){return d3;} @Override public String toString(){return label;} }
    private enum TargetType { PLAYERS("Players",true,false), MOBS("Mobs",false,true), BOTH("Both",true,true); final String label; final boolean players,mobs; TargetType(String l,boolean p,boolean m){label=l;players=p;mobs=m;} @Override public String toString(){return label;} }
}
