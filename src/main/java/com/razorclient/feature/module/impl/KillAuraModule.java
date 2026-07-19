package com.razorclient.feature.module.impl.combat;

import com.razorclient.event.impl.ClientRotationEvent;
import com.razorclient.event.impl.PrePlayerInteractEvent;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.settings.*;
import com.razorclient.util.RandomizationHelper;
import com.razorclient.util.RotationManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class KillAuraModule extends Module {
    public NumberSetting range = new NumberSetting("Range", 3.0, 5.0, 4.2, 0.1);
    public NumberSetting cps = new NumberSetting("CPS", 8.0, 20.0, 12.0, 0.5);
    public BooleanSetting silentRotate = new BooleanSetting("Silent Rotate", true);
    public BooleanSetting autoBlock = new BooleanSetting("Auto Block", true);
    public BooleanSetting walls = new BooleanSetting("Through Walls", false);
    public EnumSetting priority = new EnumSetting("Priority", "Distance", "Health", "Angle");

    private long lastAttackTime = 0;
    private int attacksThisTick = 0;

    public KillAuraModule() {
        super("Kill Aura", Category.COMBAT);
        addSettings(range, cps, silentRotate, autoBlock, walls, priority);
    }

    @Override
    public void onEnable() {
        attacksThisTick = 0;
        RotationManager.getInstance().reset();
    }

    @Override
    public void onDisable() {
        RotationManager.getInstance().reset();
    }

    @Override
    public void onClientRotation(ClientRotationEvent event) {
        if (!shouldAct()) return;
        Entity target = getTarget();
        if (target == null) return;

        float[] angles = getRotations(target);
        if (silentRotate.isEnabled()) {
            RotationManager.getInstance().setRotations(angles[0], angles[1], false);
            event.setCanceled(true);
        } else {
            mc.thePlayer.rotationYaw = angles[0];
            mc.thePlayer.rotationPitch = angles[1];
        }
    }

    @Override
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!shouldAct()) return;
        attacksThisTick++;

        long now = System.currentTimeMillis();
        long delay = getDelay();
        if (now - lastAttackTime >= delay && attacksThisTick <= 1) {
            Entity target = getTarget();
            if (target != null) {
                mc.playerController.attackEntity(mc.thePlayer, target);
                KeyBinding.onTick(mc.gameSettings.keyBindAttack);
                lastAttackTime = now;
                attacksThisTick = 0;

                if (autoBlock.isEnabled()) {
                    mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
                }
            }
        }
    }

    private Entity getTarget() {
        List<EntityLivingBase> targets = mc.theWorld.playerEntities.stream()
                .filter(e -> e != mc.thePlayer && e.isEntityAlive() && !e.isDead)
                .filter(e -> !AntiBotModule.shouldIgnore(e))
                .filter(e -> {
                    double dist = mc.thePlayer.getDistanceToEntity(e);
                    return dist <= range.getValue() && (walls.isEnabled() || mc.thePlayer.canEntityBeSeen(e));
                })
                .collect(Collectors.toList());

        if (targets.isEmpty()) return null;

        Comparator<EntityLivingBase> comp;
        switch (priority.getValue().toLowerCase()) {
            case "health": comp = Comparator.comparingDouble(e -> e.getHealth()); break;
            case "angle": comp = Comparator.comparingDouble(e -> getAngleDifference(e)); break;
            default: comp = Comparator.comparingDouble(e -> mc.thePlayer.getDistanceToEntity(e));
        }
        targets.sort(comp);
        return targets.get(0);
    }

    private float[] getRotations(Entity target) {
        double x = target.posX - mc.thePlayer.posX;
        double y = target.posY + target.getEyeHeight() - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
        double z = target.posZ - mc.thePlayer.posZ;
        double dist = MathHelper.sqrt_double(x * x + z * z);
        float yaw = (float) Math.toDegrees(-Math.atan2(x, z));
        float pitch = (float) -Math.toDegrees(Math.atan2(y, dist));
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90, 90)};
    }

    private double getAngleDifference(Entity target) {
        float[] angles = getRotations(target);
        float yawDiff = MathHelper.wrapAngleTo180_float(angles[0] - mc.thePlayer.rotationYaw);
        float pitchDiff = MathHelper.wrapAngleTo180_float(angles[1] - mc.thePlayer.rotationPitch);
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    private long getDelay() {
        double cpsVal = cps.getValue();
        double gaussian = RandomizationHelper.nextGaussian(cpsVal - 3, cpsVal + 3, 1.2);
        int finalCps = Math.max(6, Math.min(20, (int) Math.round(gaussian)));
        return 1000L / finalCps + RandomizationHelper.nextInt(-5, 5);
    }
}