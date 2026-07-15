package com.razorclient.feature.module.impl;

import com.razorclient.RazorClient;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.runtime.FrameContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

public final class BedPlatesModule extends Module {
    private static final int FALLBACK_RESCAN_INTERVAL_TICKS = 240;
    private static final int FALLBACK_RESCAN_CHUNKS_PER_TICK = 2;
    private static final int BLOCK_SCAN_BUDGET_PER_TICK = 16384;
    private static final float LABEL_BASE_SCALE = 0.026F;
    private static final float LABEL_DISTANCE_REFERENCE = 32.0F;
    private static final float LABEL_DISTANCE_MULTIPLIER = 6.0F;
    private static final float LABEL_MAX_SCALE = 0.30F;

    private final NumberSetting range = new NumberSetting("Range", 5, 128, 1, 128);
    private final NumberSetting layers = new NumberSetting("Layers", 1, 4, 1, 2);
    private final BooleanSetting showDistance = new BooleanSetting("Show Distance", true);

    private final Map<String, CachedBed> bedCache = new HashMap<String, CachedBed>();
    private final Map<Long, Set<String>> chunkBeds = new HashMap<Long, Set<String>>();
    private final Set<Long> scannedChunks = new HashSet<Long>();
    private final Deque<Long> rescanQueue = new ArrayDeque<Long>();
    private final Set<Long> queuedChunks = new HashSet<Long>();
    private final List<BedRenderInfo> renderPool = new ArrayList<BedRenderInfo>();
    private ChunkScanTask activeScan;

    private WorldClient cachedWorld;
    private int ticksSinceFallback;
    private int lastRange = range.getValue();
    private int lastLayers = layers.getValue();

    public BedPlatesModule() {
        super("BedPlates", "Shows the unique defense blocks around nearby beds.", Category.RENDER, Keyboard.KEY_NONE);
        addSetting(range);
        addSetting(layers);
        addSetting(showDistance);
    }

    @Override
    protected void onEnable() {
        resetCache();
    }

    @Override
    protected void onDisable() {
        resetCache();
    }

    @Override
    public void onSessionReset() {
        resetCache();
    }

    @Override
    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            resetCache();
            return;
        }

        if (cachedWorld != world) {
            resetCache();
            cachedWorld = world;
        }

        if (lastRange != range.getValue() || lastLayers != layers.getValue()) {
            lastRange = range.getValue();
            lastLayers = layers.getValue();
            resetChunkTracking();
            queueNearbyChunks(player, true);
        }

        boolean bedBroken = removeBrokenBeds(world);
        if (bedBroken) {
            queueNearbyChunks(player, true);
        }

        scanNewNearbyChunks(world, player);

        ticksSinceFallback++;
        if (ticksSinceFallback >= FALLBACK_RESCAN_INTERVAL_TICKS) {
            ticksSinceFallback = 0;
            queueNearbyChunks(player, true);
        }

        processQueuedRescans(world);
    }

    @Override
    public String getHudInfo() {
        return bedCache.size() + " beds";
    }

    @Override
    public void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || bedCache.isEmpty()) {
            return;
        }

        int renderCount = 0;
        double maxDistanceSq = range.getValue() * range.getValue();
        for (CachedBed cachedBed : bedCache.values()) {
            double distanceSq = getDistanceSq(player, cachedBed.first, cachedBed.second);
            if (distanceSq <= maxDistanceSq) {
                BedRenderInfo renderInfo;
                if (renderCount < renderPool.size()) {
                    renderInfo = renderPool.get(renderCount);
                } else {
                    renderInfo = new BedRenderInfo();
                    renderPool.add(renderInfo);
                }
                renderInfo.set(cachedBed.first, cachedBed.second, cachedBed.defenses, distanceSq);
                renderCount++;
            }
        }

        if (renderCount == 0) return;
        sortRenderPool(renderCount);

        RazorClient client = RazorClient.getInstance();
        FrameContext frame = client == null ? null : client.getModuleManager().getFrameContext();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            for (int i = 0; i < renderCount; i++) renderLabel(mc, frame, renderPool.get(i));
        } finally {
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glLineWidth(1.0F);
            GL11.glPopAttrib();
        }
    }

    private void sortRenderPool(int count) {
        for (int i = 1; i < count; i++) {
            BedRenderInfo value = renderPool.get(i);
            int insert = i;
            while (insert > 0 && renderPool.get(insert - 1).distanceSq > value.distanceSq) {
                renderPool.set(insert, renderPool.get(insert - 1));
                insert--;
            }
            renderPool.set(insert, value);
        }
    }

    private void scanNewNearbyChunks(WorldClient world, EntityPlayerSP player) {
        int chunkRadius = getChunkRadius();
        int playerChunkX = MathHelper.floor_double(player.posX) >> 4;
        int playerChunkZ = MathHelper.floor_double(player.posZ) >> 4;

        for (int chunkX = playerChunkX - chunkRadius; chunkX <= playerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = playerChunkZ - chunkRadius; chunkZ <= playerChunkZ + chunkRadius; chunkZ++) {
                long chunkKey = makeChunkKey(chunkX, chunkZ);
                if (scannedChunks.contains(chunkKey) || queuedChunks.contains(chunkKey)
                        || !isChunkLoaded(world, chunkX, chunkZ)) {
                    continue;
                }
                queueChunk(chunkKey);
            }
        }
    }

    private boolean removeBrokenBeds(WorldClient world) {
        boolean removed = false;
        Iterator<Map.Entry<String, CachedBed>> iterator = bedCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedBed> entry = iterator.next();
            CachedBed bed = entry.getValue();
            if (isBedBlock(world, bed.first) && isBedBlock(world, bed.second)) continue;
            iterator.remove();
            long ownerChunkKey = getOwnerChunkKey(bed.first);
            Set<String> keys = chunkBeds.get(ownerChunkKey);
            if (keys != null) {
                keys.remove(entry.getKey());
                if (keys.isEmpty()) {
                    chunkBeds.remove(ownerChunkKey);
                }
            }
            removed = true;
        }
        return removed;
    }

    private void queueNearbyChunks(EntityPlayerSP player, boolean resetScannedState) {
        int chunkRadius = getChunkRadius();
        int playerChunkX = MathHelper.floor_double(player.posX) >> 4;
        int playerChunkZ = MathHelper.floor_double(player.posZ) >> 4;

        for (int chunkX = playerChunkX - chunkRadius; chunkX <= playerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = playerChunkZ - chunkRadius; chunkZ <= playerChunkZ + chunkRadius; chunkZ++) {
                long chunkKey = makeChunkKey(chunkX, chunkZ);
                if (resetScannedState) {
                    scannedChunks.remove(chunkKey);
                }
                queueChunk(chunkKey);
            }
        }
    }

    private void processQueuedRescans(WorldClient world) {
        int budget = BLOCK_SCAN_BUDGET_PER_TICK;
        int completed = 0;
        while (budget > 0 && completed < FALLBACK_RESCAN_CHUNKS_PER_TICK) {
            if (activeScan != null && !isChunkLoaded(
                    world,
                    unpackChunkX(activeScan.chunkKey),
                    unpackChunkZ(activeScan.chunkKey))) {
                queuedChunks.remove(activeScan.chunkKey);
                scannedChunks.remove(activeScan.chunkKey);
                removeChunkBeds(activeScan.chunkKey);
                activeScan = null;
                completed++;
                continue;
            }
            if (activeScan == null) {
                if (rescanQueue.isEmpty()) return;
                long chunkKey = rescanQueue.removeFirst().longValue();
                int chunkX = unpackChunkX(chunkKey);
                int chunkZ = unpackChunkZ(chunkKey);
                if (!isChunkLoaded(world, chunkX, chunkZ)) {
                    queuedChunks.remove(chunkKey);
                    scannedChunks.remove(chunkKey);
                    removeChunkBeds(chunkKey);
                    completed++;
                    continue;
                }
                activeScan = new ChunkScanTask(chunkKey, world.getChunkFromChunkCoords(chunkX, chunkZ));
            }

            int consumed = scanChunkSlice(world, activeScan, budget);
            budget -= consumed;
            if (!activeScan.complete) return;

            finishChunkScan(activeScan);
            activeScan = null;
            completed++;
        }
    }

    private int scanChunkSlice(WorldClient world, ChunkScanTask task, int budget) {
        ExtendedBlockStorage[] sections = task.chunk.getBlockStorageArray();
        int consumed = 0;
        while (task.sectionIndex < sections.length && consumed < budget) {
            ExtendedBlockStorage section = sections[task.sectionIndex];
            if (section == null || section.isEmpty()) {
                task.sectionIndex++;
                task.blockIndex = 0;
                continue;
            }

            while (task.blockIndex < 4096 && consumed < budget) {
                int index = task.blockIndex++;
                consumed++;
                int localX = index & 15;
                int localZ = (index >>> 4) & 15;
                int localY = (index >>> 8) & 15;
                if (!(section.getBlockByExtId(localX, localY, localZ) instanceof BlockBed)) continue;

                BlockPos pos = new BlockPos(
                    (task.chunk.xPosition << 4) + localX,
                    (task.sectionIndex << 4) + localY,
                    (task.chunk.zPosition << 4) + localZ
                );
                BedPair pair = resolveBedPair(world, pos);
                if (getOwnerChunkKey(pair.first) != task.chunkKey) continue;
                String bedKey = makeBedKey(pair.first, pair.second);
                if (!task.foundBeds.add(bedKey)) continue;
                bedCache.put(bedKey, new CachedBed(
                    pair.first,
                    pair.second,
                    collectDefenseBlocks(world, pair.first, pair.second)
                ));
            }

            if (task.blockIndex >= 4096) {
                task.sectionIndex++;
                task.blockIndex = 0;
            }
        }
        task.complete = task.sectionIndex >= sections.length;
        return consumed;
    }

    private void finishChunkScan(ChunkScanTask task) {
        Set<String> previousBeds = chunkBeds.put(task.chunkKey, task.foundBeds);
        if (previousBeds != null) {
            for (String previousKey : previousBeds) {
                if (!task.foundBeds.contains(previousKey)) {
                    bedCache.remove(previousKey);
                }
            }
        }
        if (task.foundBeds.isEmpty()) {
            chunkBeds.remove(task.chunkKey);
        }
        queuedChunks.remove(task.chunkKey);
        scannedChunks.add(task.chunkKey);
    }

    private void queueChunk(long chunkKey) {
        if (queuedChunks.add(chunkKey)) rescanQueue.addLast(Long.valueOf(chunkKey));
    }

    private void removeChunkBeds(long chunkKey) {
        Set<String> keys = chunkBeds.remove(chunkKey);
        if (keys == null) return;
        for (String key : keys) bedCache.remove(key);
    }

    private BedPair resolveBedPair(WorldClient world, BlockPos pos) {
        BlockPos otherPart = pos;
        BlockPos[] neighbors = new BlockPos[] {
            pos.north(),
            pos.south(),
            pos.east(),
            pos.west()
        };
        for (BlockPos neighbor : neighbors) {
            if (isBedBlock(world, neighbor)) {
                otherPart = neighbor;
                break;
            }
        }

        BlockPos first = comparePos(pos, otherPart) <= 0 ? pos : otherPart;
        BlockPos second = comparePos(pos, otherPart) <= 0 ? otherPart : pos;
        return new BedPair(first, second);
    }

    private Set<String> collectDefenseBlocks(WorldClient world, BlockPos first, BlockPos second) {
        Set<String> names = new LinkedHashSet<String>();
        int radius = layers.getValue();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = 0; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    addDefenseBlock(world, names, first.add(dx, dy, dz));
                    addDefenseBlock(world, names, second.add(dx, dy, dz));
                }
            }
        }

        return names;
    }

    private void addDefenseBlock(WorldClient world, Set<String> names, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        if (block == null || block == Blocks.air || block instanceof BlockBed || block.getMaterial() == Material.air) {
            return;
        }
        names.add(cleanBlockName(block));
    }

    private boolean isBedBlock(WorldClient world, BlockPos pos) {
        return world.getBlockState(pos).getBlock() instanceof BlockBed;
    }

    private boolean isChunkLoaded(WorldClient world, int chunkX, int chunkZ) {
        return world.getChunkProvider().chunkExists(chunkX, chunkZ);
    }

    private int getChunkRadius() {
        return Math.max(1, (range.getValue() + 15) >> 4);
    }

    private double getDistanceSq(EntityPlayerSP player, BlockPos first, BlockPos second) {
        double centerX = (first.getX() + second.getX()) / 2.0D + 0.5D;
        double centerY = Math.max(first.getY(), second.getY()) + 0.5D;
        double centerZ = (first.getZ() + second.getZ()) / 2.0D + 0.5D;
        double deltaX = player.posX - centerX;
        double deltaY = player.posY - centerY;
        double deltaZ = player.posZ - centerZ;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private String cleanBlockName(Block block) {
        String name = block.getLocalizedName();
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }

        Object fallbackObject = Block.blockRegistry.getNameForObject(block);
        String fallback = fallbackObject == null ? null : fallbackObject.toString();
        if (fallback == null) {
            return "Unknown";
        }
        int separator = fallback.indexOf(':');
        String simple = separator >= 0 ? fallback.substring(separator + 1) : fallback;
        return simple.replace('_', ' ');
    }

    private void renderLabel(Minecraft mc, FrameContext frame, BedRenderInfo bed) {
        FontRenderer font = mc.fontRendererObj;
        double viewerX = frame == null ? mc.getRenderManager().viewerPosX : frame.getCameraX();
        double viewerY = frame == null ? mc.getRenderManager().viewerPosY : frame.getCameraY();
        double viewerZ = frame == null ? mc.getRenderManager().viewerPosZ : frame.getCameraZ();

        double x = (bed.first.getX() + bed.second.getX()) / 2.0D + 0.5D - viewerX;
        double y = Math.max(bed.first.getY(), bed.second.getY()) + 1.35D - viewerY;
        double z = (bed.first.getZ() + bed.second.getZ()) / 2.0D + 0.5D - viewerZ;

        String defenseText = bed.defenses.isEmpty() ? "Uncovered" : joinNames(bed.defenses);
        if (showDistance.isEnabled()) {
            defenseText = defenseText + String.format(Locale.US, " [%.1fm]", Math.sqrt(bed.distanceSq));
        }

        float scale = getLabelScale(bed.distanceSq);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, z);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(mc.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
            GlStateManager.scale(-scale, -scale, scale);
            GlStateManager.disableLighting();
            GlStateManager.depthMask(false);
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            int width = font.getStringWidth(defenseText) / 2;
            drawBackground(width);
            font.drawString(defenseText, -width, 0, 0x20FFFFFF);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            font.drawString(defenseText, -width, 0, 0xFFFFFFFF);
        } finally {
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private float getLabelScale(double distanceSq) {
        float distance = (float) Math.sqrt(distanceSq);
        float scaled = LABEL_BASE_SCALE * Math.max(1.0F, (distance / LABEL_DISTANCE_REFERENCE) * LABEL_DISTANCE_MULTIPLIER);
        return Math.min(LABEL_MAX_SCALE, scaled);
    }

    private void drawBackground(int halfWidth) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        GlStateManager.disableTexture2D();
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        renderer.pos(-halfWidth - 2, -2, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        renderer.pos(-halfWidth - 2, 10, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        renderer.pos(halfWidth + 2, 10, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        renderer.pos(halfWidth + 2, -2, 0.0D).color(0.0F, 0.0F, 0.0F, 0.45F).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    private String joinNames(Set<String> names) {
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String name : names) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(name);
            index++;
            if (builder.length() > 48 && index < names.size()) {
                builder.append("...");
                break;
            }
        }
        return builder.toString();
    }

    private void resetCache() {
        cachedWorld = null;
        ticksSinceFallback = 0;
        bedCache.clear();
        renderPool.clear();
        resetChunkTracking();
    }

    private void resetChunkTracking() {
        chunkBeds.clear();
        scannedChunks.clear();
        rescanQueue.clear();
        queuedChunks.clear();
        activeScan = null;
    }

    private long getOwnerChunkKey(BlockPos pos) {
        return makeChunkKey(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private long makeChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private int unpackChunkX(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    private int unpackChunkZ(long chunkKey) {
        return (int) chunkKey;
    }

    private String makeBedKey(BlockPos first, BlockPos second) {
        return first.getX() + ":" + first.getY() + ":" + first.getZ() + "|" + second.getX() + ":" + second.getY() + ":" + second.getZ();
    }

    private int comparePos(BlockPos left, BlockPos right) {
        if (left.getY() != right.getY()) {
            return left.getY() - right.getY();
        }
        if (left.getX() != right.getX()) {
            return left.getX() - right.getX();
        }
        return left.getZ() - right.getZ();
    }

    private static final class BedPair {
        private final BlockPos first;
        private final BlockPos second;

        private BedPair(BlockPos first, BlockPos second) {
            this.first = first;
            this.second = second;
        }
    }

    private static final class ChunkScanTask {
        private final long chunkKey;
        private final Chunk chunk;
        private final Set<String> foundBeds = new HashSet<String>();
        private int sectionIndex;
        private int blockIndex;
        private boolean complete;

        private ChunkScanTask(long chunkKey, Chunk chunk) {
            this.chunkKey = chunkKey;
            this.chunk = chunk;
        }
    }

    private static final class CachedBed {
        private final BlockPos first;
        private final BlockPos second;
        private final Set<String> defenses;

        private CachedBed(BlockPos first, BlockPos second, Set<String> defenses) {
            this.first = first;
            this.second = second;
            this.defenses = defenses;
        }
    }

    private static final class BedRenderInfo {
        private BlockPos first;
        private BlockPos second;
        private Set<String> defenses;
        private double distanceSq;

        private void set(BlockPos first, BlockPos second, Set<String> defenses, double distanceSq) {
            this.first = first;
            this.second = second;
            this.defenses = defenses;
            this.distanceSq = distanceSq;
        }
    }
}
