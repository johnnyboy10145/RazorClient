package com.razorclient.feature.module.impl;

import com.razorclient.combat.CombatTargetService;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.AxisAlignedBB;
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
    private final List<ProjectedSnapshot> projected = new ArrayList<ProjectedSnapshot>();
    private volatile List<EspSnapshot> snapshots = Collections.emptyList();
    private volatile int renderedCount;

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
        if (mc.theWorld == null || mc.thePlayer == null) {
            clearRenderState();
            return;
        }
        List<EspSnapshot> next = new ArrayList<EspSnapshot>(Math.min(MAX_SNAPSHOTS, mc.theWorld.loadedEntityList.size()));
        int activeTarget = CombatTargetService.getPublishedTargetId(mc);
        double limit = maxDistance.getValue();
        for (Object value : mc.theWorld.loadedEntityList) {
            if (next.size() >= MAX_SNAPSHOTS || !(value instanceof EntityLivingBase)) continue;
            EntityLivingBase entity = (EntityLivingBase) value;
            if (!isEligible(mc, entity, limit)) continue;
            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box == null) continue;
            boolean visible = mc.thePlayer.canEntityBeSeen(entity);
            if (!throughWalls.isEnabled() && !visible) continue;
            ItemStack held = entity.getHeldItem();
            String heldName = held == null ? "" : held.getDisplayName();
            int teamColor = entity instanceof EntityPlayer ? getTeamColor((EntityPlayer) entity) : -1;
            next.add(new EspSnapshot(entity.getEntityId(), entity.getName(), entity instanceof EntityPlayer,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                entity.getHealth(), Math.max(1.0F, entity.getMaxHealth()), entity.getTotalArmorValue(),
                heldName, mc.thePlayer.getDistanceToEntity(entity), visible, entity.getEntityId() == activeTarget, teamColor));
        }
        snapshots = Collections.unmodifiableList(next);
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.getRenderManager() == null) return;
        List<EspSnapshot> frame = snapshots;
        renderedCount = frame.size();
        projected.clear();
        if (projectionMode.getValue().draws3d()) render3d(mc, frame);
        if (projectionMode.getValue().draws2d()) project2d(mc, frame);
    }

    @Override
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!projectionMode.getValue().draws2d() || projected.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.fontRendererObj == null) return;
        render2d(mc, new ScaledResolution(mc));
    }

    @Override protected void onDisable() { clearRenderState(); }
    @Override public void onSessionReset() { clearRenderState(); }
    @Override public void onInputContextLost() { projected.clear(); }

    private boolean isEligible(Minecraft mc, EntityLivingBase entity, double limit) {
        if (entity == mc.thePlayer || entity.isDead || entity.deathTime != 0 || entity.getHealth() <= 0.0F) return false;
        boolean player = entity instanceof EntityPlayer;
        boolean mob = entity instanceof EntityMob || entity instanceof EntityAnimal;
        if ((player && !targetType.getValue().players) || (mob && !targetType.getValue().mobs) || (!player && !mob)) return false;
        return CombatTargetService.isValid(mc, entity, player, mob, seeInvis.isEnabled(), false, false, limit);
    }

    private void render3d(Minecraft mc, List<EspSnapshot> frame) {
        double viewX = mc.getRenderManager().viewerPosX;
        double viewY = mc.getRenderManager().viewerPosY;
        double viewZ = mc.getRenderManager().viewerPosZ;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GlStateManager.disableTexture2D(); GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableLighting(); GlStateManager.disableCull();
            if (throughWalls.isEnabled()) { GlStateManager.disableDepth(); GlStateManager.depthMask(false); }
            GL11.glLineWidth(lineWidth.getValue());
            for (EspSnapshot snapshot : frame) {
                int color = colorFor(snapshot);
                float r = ((color >>> 16) & 255) / 255.0F;
                float g = ((color >>> 8) & 255) / 255.0F;
                float b = (color & 255) / 255.0F;
                AxisAlignedBB box = new AxisAlignedBB(snapshot.minX - viewX, snapshot.minY - viewY, snapshot.minZ - viewZ,
                    snapshot.maxX - viewX, snapshot.maxY - viewY, snapshot.maxZ - viewZ).expand(0.04D, 0.08D, 0.04D);
                if (renderMode.getValue().fill) drawFilledBox(box, r, g, b, fillAlpha.getValue() / 100.0F);
                if (renderMode.getValue().outline) drawOutlinedBox(box, r, g, b, 1.0F);
            }
        } finally {
            GlStateManager.depthMask(true); GlStateManager.enableDepth(); GlStateManager.enableCull();
            GlStateManager.enableTexture2D(); GlStateManager.disableBlend();
            GlStateManager.color(1, 1, 1, 1); GL11.glLineWidth(1.0F);
            GL11.glPopMatrix(); GL11.glPopAttrib();
        }
    }

    private void project2d(Minecraft mc, List<EspSnapshot> frame) {
        modelView.clear(); projection.clear(); viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        ScaledResolution scaled = new ScaledResolution(mc);
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;
        for (EspSnapshot snapshot : frame) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            boolean any = false;
            for (int corner = 0; corner < 8; corner++) {
                double x = ((corner & 1) == 0 ? snapshot.minX : snapshot.maxX) - viewerX;
                double y = ((corner & 2) == 0 ? snapshot.minY : snapshot.maxY) - viewerY;
                double z = ((corner & 4) == 0 ? snapshot.minZ : snapshot.maxZ) - viewerZ;
                projectedPoint.clear();
                if (!GLU.gluProject((float) x, (float) y, (float) z, modelView, projection, viewport, projectedPoint)) continue;
                float depth = projectedPoint.get(2);
                if (depth < 0.0F || depth > 1.0F) continue;
                float sx = projectedPoint.get(0) * scaled.getScaledWidth() / Math.max(1.0F, mc.displayWidth);
                float sy = (mc.displayHeight - projectedPoint.get(1)) * scaled.getScaledHeight() / Math.max(1.0F, mc.displayHeight);
                minX = Math.min(minX, sx); minY = Math.min(minY, sy); maxX = Math.max(maxX, sx); maxY = Math.max(maxY, sy); any = true;
            }
            if (any && maxX >= 0 && maxY >= 0 && minX <= scaled.getScaledWidth() && minY <= scaled.getScaledHeight()) {
                projected.add(new ProjectedSnapshot(snapshot, minX, minY, maxX, maxY));
            }
        }
    }

    private void render2d(Minecraft mc, ScaledResolution resolution) {
        FontRenderer font = mc.fontRendererObj;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GlStateManager.enableBlend(); GlStateManager.disableDepth(); GlStateManager.depthMask(false);
            for (ProjectedSnapshot projection : projected) {
                EspSnapshot snapshot = projection.snapshot;
                int color = 0xFF000000 | colorFor(snapshot);
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
            GlStateManager.enableTexture2D(); GlStateManager.color(1, 1, 1, 1); GL11.glPopAttrib();
        }
    }

    private void drawInformation(FontRenderer font, EspSnapshot s, int x1, int y1, int x2, int y2, int color) {
        StringBuilder top = new StringBuilder();
        if (showNames.isEnabled()) top.append(s.name);
        if (showHealth.isEnabled() && healthValue.isEnabled()) append(top, Math.round(s.health) + "hp");
        if (showDistance.isEnabled()) append(top, String.format(Locale.ROOT, "%.1fm", s.distance));
        if (top.length() > 0) font.drawStringWithShadow(top.toString(), (x1 + x2 - font.getStringWidth(top.toString())) / 2.0F, y1 - 10, color);
        StringBuilder bottom = new StringBuilder();
        if (showArmor.isEnabled()) append(bottom, "Armor " + s.armor);
        if (showHeldItem.isEnabled() && !s.heldItem.isEmpty()) append(bottom, s.heldItem);
        if (bottom.length() > 0) font.drawStringWithShadow(bottom.toString(), (x1 + x2 - font.getStringWidth(bottom.toString())) / 2.0F, y2 + 2, 0xFFFFFFFF);
    }

    private void append(StringBuilder builder, String value) { if (builder.length() > 0) builder.append(" | "); builder.append(value); }
    private void drawHealthBar(EspSnapshot s, int x, int top, int bottom) {
        float ratio = Math.max(0.0F, Math.min(1.0F, s.health / s.maxHealth));
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

    private int colorFor(EspSnapshot snapshot) {
        if (targetHighlight.isEnabled() && snapshot.target) return rgb(targetRed, targetGreen, targetBlue);
        if (!snapshot.visible) return rgb(hiddenRed, hiddenGreen, hiddenBlue);
        if (useTeamColors.isEnabled() && snapshot.teamColor >= 0) return snapshot.teamColor;
        if (mode.getValue() == Mode.MODERN) {
            float wave = (float) ((Math.sin(System.nanoTime() / 340000000.0D + snapshot.id * 0.35D) + 1.0D) * 0.5D);
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

    private void clearRenderState() { snapshots = Collections.emptyList(); projected.clear(); renderedCount = 0; }
    @Override public String getHudInfo() { return projectionMode.getValue() + " " + renderedCount; }

    private void drawOutlinedBox(AxisAlignedBB b, float r, float g, float blue, float a) {
        GlStateManager.color(r, g, blue, a); GL11.glBegin(GL11.GL_LINES);
        edge(b.minX,b.minY,b.minZ,b.maxX,b.minY,b.minZ); edge(b.maxX,b.minY,b.minZ,b.maxX,b.minY,b.maxZ); edge(b.maxX,b.minY,b.maxZ,b.minX,b.minY,b.maxZ); edge(b.minX,b.minY,b.maxZ,b.minX,b.minY,b.minZ);
        edge(b.minX,b.maxY,b.minZ,b.maxX,b.maxY,b.minZ); edge(b.maxX,b.maxY,b.minZ,b.maxX,b.maxY,b.maxZ); edge(b.maxX,b.maxY,b.maxZ,b.minX,b.maxY,b.maxZ); edge(b.minX,b.maxY,b.maxZ,b.minX,b.maxY,b.minZ);
        edge(b.minX,b.minY,b.minZ,b.minX,b.maxY,b.minZ); edge(b.maxX,b.minY,b.minZ,b.maxX,b.maxY,b.minZ); edge(b.maxX,b.minY,b.maxZ,b.maxX,b.maxY,b.maxZ); edge(b.minX,b.minY,b.maxZ,b.minX,b.maxY,b.maxZ); GL11.glEnd();
    }
    private void edge(double a,double b,double c,double d,double e,double f){GL11.glVertex3d(a,b,c);GL11.glVertex3d(d,e,f);}
    private void drawFilledBox(AxisAlignedBB b,float r,float g,float blue,float a){
        GlStateManager.color(r,g,blue,a); GL11.glBegin(GL11.GL_QUADS);
        quad(b.minX,b.minY,b.minZ,b.maxX,b.minY,b.minZ,b.maxX,b.maxY,b.minZ,b.minX,b.maxY,b.minZ); quad(b.minX,b.minY,b.maxZ,b.maxX,b.minY,b.maxZ,b.maxX,b.maxY,b.maxZ,b.minX,b.maxY,b.maxZ);
        quad(b.minX,b.minY,b.minZ,b.minX,b.minY,b.maxZ,b.minX,b.maxY,b.maxZ,b.minX,b.maxY,b.minZ); quad(b.maxX,b.minY,b.minZ,b.maxX,b.minY,b.maxZ,b.maxX,b.maxY,b.maxZ,b.maxX,b.maxY,b.minZ); GL11.glEnd();
    }
    private void quad(double a,double b,double c,double d,double e,double f,double g,double h,double i,double j,double k,double l){GL11.glVertex3d(a,b,c);GL11.glVertex3d(d,e,f);GL11.glVertex3d(g,h,i);GL11.glVertex3d(j,k,l);}

    private static final class EspSnapshot {
        final int id; final String name; final boolean player; final double minX,minY,minZ,maxX,maxY,maxZ;
        final float health,maxHealth; final int armor; final String heldItem; final float distance; final boolean visible,target; final int teamColor;
        EspSnapshot(int id,String name,boolean player,double minX,double minY,double minZ,double maxX,double maxY,double maxZ,float health,float maxHealth,int armor,String heldItem,float distance,boolean visible,boolean target,int teamColor){
            this.id=id;this.name=name;this.player=player;this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;this.health=health;this.maxHealth=maxHealth;this.armor=armor;this.heldItem=heldItem;this.distance=distance;this.visible=visible;this.target=target;this.teamColor=teamColor;
        }
    }
    private static final class ProjectedSnapshot { final EspSnapshot snapshot; final float minX,minY,maxX,maxY; ProjectedSnapshot(EspSnapshot s,float x1,float y1,float x2,float y2){snapshot=s;minX=x1;minY=y1;maxX=x2;maxY=y2;} }
    private enum Mode { MODERN("Modern"), CLASSIC("Classic"); final String label; Mode(String l){label=l;} @Override public String toString(){return label;} }
    private enum RenderMode { BOX("Box",true,false), OUTLINE("Outline",false,true), BOTH("Both",true,true); final String label; final boolean fill,outline; RenderMode(String l,boolean f,boolean o){label=l;fill=f;outline=o;} @Override public String toString(){return label;} }
    private enum ProjectionMode { TWO_D("2D",true,false), THREE_D("3D",false,true), BOTH("Both",true,true); final String label; final boolean d2,d3; ProjectionMode(String l,boolean a,boolean b){label=l;d2=a;d3=b;} boolean draws2d(){return d2;} boolean draws3d(){return d3;} @Override public String toString(){return label;} }
    private enum TargetType { PLAYERS("Players",true,false), MOBS("Mobs",false,true), BOTH("Both",true,true); final String label; final boolean players,mobs; TargetType(String l,boolean p,boolean m){label=l;players=p;mobs=m;} @Override public String toString(){return label;} }
}
