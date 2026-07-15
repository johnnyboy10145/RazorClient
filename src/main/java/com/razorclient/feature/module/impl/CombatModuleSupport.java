package com.razorclient.feature.module.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.item.ItemTool;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Stateless helpers shared by combat modules; runtime decisions remain module-local. */
final class CombatModuleSupport {
    private CombatModuleSupport() {
    }

    static boolean inGame(Minecraft minecraft) {
        return minecraft != null
            && minecraft.thePlayer != null
            && minecraft.theWorld != null
            && !minecraft.thePlayer.isDead;
    }

    static boolean isAttackPacket(Packet<?> packet) {
        return packet instanceof C02PacketUseEntity
            && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK;
    }

    static Entity attackTarget(Minecraft minecraft, Packet<?> packet) {
        if (!inGame(minecraft) || !isAttackPacket(packet)) {
            return null;
        }
        return ((C02PacketUseEntity) packet).getEntityFromWorld(minecraft.theWorld);
    }

    static EntityLivingBase crosshairLivingTarget(Minecraft minecraft) {
        if (!inGame(minecraft) || minecraft.objectMouseOver == null) {
            return null;
        }
        Entity entity = minecraft.objectMouseOver.entityHit;
        return entity instanceof EntityLivingBase ? (EntityLivingBase) entity : null;
    }

    static boolean attackButtonDown(Minecraft minecraft) {
        return inGame(minecraft) && physicalKeyDown(minecraft.gameSettings.keyBindAttack.getKeyCode());
    }

    static boolean physicalKeyDown(int keyCode) {
        try {
            if (keyCode < 0) {
                int button = keyCode + 100;
                return Mouse.isCreated() && button >= 0 && Mouse.isButtonDown(button);
            }
            return keyCode != Keyboard.KEY_NONE && Keyboard.isCreated() && Keyboard.isKeyDown(keyCode);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean holdingWeapon(Minecraft minecraft) {
        return inGame(minecraft) && isWeapon(minecraft.thePlayer.getHeldItem());
    }

    static boolean isWeapon(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof ItemSword || item instanceof ItemTool;
    }

    static double weaponDamage(ItemStack stack, EntityLivingBase target) {
        if (!isWeapon(stack)) {
            return Double.NEGATIVE_INFINITY;
        }
        Item item = stack.getItem();
        double base = item instanceof ItemSword
            ? ((ItemSword) item).getDamageVsEntity()
            : ((ItemTool) item).damageVsEntity;
        EnumCreatureAttribute attribute = target == null
            ? EnumCreatureAttribute.UNDEFINED : target.getCreatureAttribute();
        return base + EnchantmentHelper.getModifierForCreature(stack, attribute);
    }

    static boolean validEnemyPlayer(Minecraft minecraft, Entity entity) {
        if (!(entity instanceof EntityPlayer) || entity == minecraft.thePlayer) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) entity;
        return !player.isDead && player.getHealth() > 0.0F
            && !AntiBotModule.shouldIgnore(player)
            && !TeamsModule.isTeammate(player);
    }
}
