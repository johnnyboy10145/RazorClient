package com.razorclient.feature.module.impl.clutch;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;

/** Performs one validated controller action, with fallback only for unavailable linkage. */
public final class ClutchPlacementExecutor {
    public boolean attempt(Minecraft mc, EntityPlayerSP player, MovingObjectPosition hit,
            ClutchCandidate candidate) {
        if (mc == null || mc.theWorld == null || mc.playerController == null || player == null
                || hit == null || hit.hitVec == null || candidate == null
                || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !candidate.getNeighbor().equals(hit.getBlockPos())
                || candidate.getFace() != hit.sideHit) return false;
        ItemStack held = player.getHeldItem();
        if (!isValidBlockStack(held)) return false;

        MovingObjectPosition previous = mc.objectMouseOver;
        mc.objectMouseOver = hit;
        try {
            boolean accepted = mc.playerController.onPlayerRightClick(player, mc.theWorld, held,
                hit.getBlockPos(), hit.sideHit, hit.hitVec);
            if (accepted) player.swingItem();
            // False can still mean that the controller transmitted C08; confirmation decides success.
            return true;
        } catch (NoSuchMethodError | AbstractMethodError unavailable) {
            return fallback(player, held, hit, candidate);
        } finally {
            mc.objectMouseOver = previous;
        }
    }

    public static boolean isValidBlockStack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) return false;
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return block != null && block.isFullCube() && !(block instanceof BlockLiquid);
    }

    private static boolean fallback(EntityPlayerSP player, ItemStack held, MovingObjectPosition hit,
            ClutchCandidate candidate) {
        if (player.sendQueue == null || !isValidBlockStack(held)
                || !candidate.getNeighbor().equals(hit.getBlockPos())
                || candidate.getFace() != hit.sideHit) return false;
        float x = (float) (hit.hitVec.xCoord - hit.getBlockPos().getX());
        float y = (float) (hit.hitVec.yCoord - hit.getBlockPos().getY());
        float z = (float) (hit.hitVec.zCoord - hit.getBlockPos().getZ());
        player.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(hit.getBlockPos(),
            hit.sideHit.getIndex(), held,
            MathHelper.clamp_float(x, 0.0F, 1.0F),
            MathHelper.clamp_float(y, 0.0F, 1.0F),
            MathHelper.clamp_float(z, 0.0F, 1.0F)));
        player.swingItem();
        return true;
    }
}
