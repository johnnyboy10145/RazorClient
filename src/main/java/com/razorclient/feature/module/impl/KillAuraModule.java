package com.razorclient.feature.module.impl;

import com.razorclient.combat.CombatTargetService;
import com.razorclient.combat.CombatActionCoordinator;
import com.razorclient.combat.ClientRotationHelper;
import com.razorclient.combat.KillAuraRotationUtils;
import com.razorclient.event.ClientRotationEvent;
import com.razorclient.event.PrePlayerInteractEvent;
import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class KillAuraModule extends Module {
    private final DecimalSetting targetCps = new DecimalSetting("Target CPS", 1.0D, 20.0D, 0.5D, 17.0D);
    private final DecimalSetting attackRange = new DecimalSetting("Range (Attack)", 3.0D, 6.0D, 0.05D, 3.0D);
    private final DecimalSetting swingRange = new DecimalSetting("Range (Swing)", 3.0D, 8.0D, 0.05D, 4.5D);
    private final DecimalSetting aimRange = new DecimalSetting("Range (Aim)", 3.0D, 8.0D, 0.05D, 4.5D);
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 1, 180, 1, 10);
    private final EnumSetting<RotationMode> rotationMode = new EnumSetting<RotationMode>(
        "Rotation Mode", RotationMode.values(), RotationMode.SILENT);
    private final EnumSetting<TargetPriority> targetPriority = new EnumSetting<TargetPriority>(
        "Target Priority", TargetPriority.values(), TargetPriority.HEALTH);
    private final NumberSetting switchDelay = new NumberSetting("Switch Delay", 50, 1000, 25, 50);
    private final NumberSetting targets = new NumberSetting("Targets", 1, 10, 1, 3);
    private final EnumSetting<TargetType> targetType = new EnumSetting<TargetType>("Target Type", TargetType.values(), TargetType.PLAYERS);
    private final BooleanSetting targetInvis = new BooleanSetting("Target Invis", true);
    private final BooleanSetting hitThroughEntities = new BooleanSetting("Hit Through Entities", false);
    private final BooleanSetting disableInInventory = new BooleanSetting("Disable In Inventory", true);
    private final BooleanSetting disableWhileMining = new BooleanSetting("Disable While Mining", true);
    private final BooleanSetting notUsingItem = new BooleanSetting("Not Using Item", false);
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false);

    private final Map<Integer, Integer> hitMap = new HashMap<Integer, Integer>();
    private final Random random = getScope().getRandom();
    private final java.lang.reflect.Field pointedEntityField;

    private EntityLivingBase target;
    private EntityLivingBase attackingEntity;
    private double targetDistance = Double.MAX_VALUE;
    private long nextClickTime;
    private boolean forgeRegistered;
    private RotationMode lastRotationMode;

    public KillAuraModule() {
        super("KillAura", "Automatically attacks enemies.", Category.COMBAT, Keyboard.KEY_NONE);
        addSetting(targetCps);
        addSetting(attackRange);
        addSetting(swingRange);
        addSetting(aimRange);
        addSetting(rotationSpeed);
        addSetting(rotationMode);
        addSetting(targetPriority);
        addSetting(switchDelay);
        addSetting(targets);
        addSetting(targetType);
        addSetting(targetInvis);
        addSetting(hitThroughEntities);
        addSetting(disableInInventory);
        addSetting(disableWhileMining);
        addSetting(notUsingItem);
        addSetting(weaponOnly);
        pointedEntityField = findRendererField("field_78528_u", "pointedEntity");
    }

    @Override
    protected void onEnable() {
        hitMap.clear();
        clearTargetState();
        lastRotationMode = rotationMode.getValue();
        registerForge();
    }

    @Override
    protected void onDisable() {
        unregisterForge();
        hitMap.clear();
        clearTargetState();
        ClientRotationHelper.get().clearRequestedRotations("KillAura");
    }

    @Override
    public void onSessionReset() {
        hitMap.clear();
        clearTargetState();
        ClientRotationHelper.get().clearRequestedRotations("KillAura");
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        RotationMode selectedRotationMode = rotationMode.getValue();
        if (selectedRotationMode != lastRotationMode) {
            ClientRotationHelper.get().clearRequestedRotations("KillAura");
            lastRotationMode = selectedRotationMode;
        }
        if (!basicCondition(minecraft) || !settingCondition(minecraft)) {
            clearTargetState();
            return;
        }

        handleTarget(minecraft);
        if (target == null) {
            attackingEntity = null;
            return;
        }

        targetDistance = KillAuraRotationUtils.distanceFromEyeToClosestOnAABB(target);
        attackingEntity = targetDistance <= attackRange.getValue() ? target : null;

        double aimRangeValue = aimRange.getValue();
        if (targetDistance > aimRangeValue) {
            return;
        }

        boolean visibleRotation = selectedRotationMode == RotationMode.VISIBLE;
        float baseYaw = visibleRotation ? minecraft.thePlayer.rotationYaw
            : (event.yaw != null ? event.yaw.floatValue() : resolveBaseYaw(minecraft));
        float basePitch = visibleRotation ? minecraft.thePlayer.rotationPitch
            : (event.pitch != null ? event.pitch.floatValue() : resolveBasePitch(minecraft));
        float[] rotations = KillAuraRotationUtils.getRotationsWithBackup(
            target,
            100.0D,
            100.0D,
            baseYaw,
            basePitch,
            aimRangeValue,
            false,
            hitThroughEntities.isEnabled()
        );
        if (rotations == null) {
            return;
        }

        float[] smooth = KillAuraRotationUtils.smoothRotation(baseYaw, basePitch, rotations[0], rotations[1],
            rotationSpeed.getValue(), 0.0F);
        if (ClientRotationHelper.get().requestRotations("KillAura", 100, smooth[0], smooth[1])) {
            event.yaw = Float.valueOf(smooth[0]);
            event.pitch = Float.valueOf(smooth[1]);
            if (visibleRotation) {
                applyVisibleRotation(minecraft, smooth[0], smooth[1]);
            }
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || minecraft.theWorld == null) {
            return;
        }
        if (target == null || targetDistance > swingRange.getValue()) {
            return;
        }

        long now = System.nanoTime();
        if (nextClickTime == 0L) {
            nextClickTime = now;
        }

        if (nextClickTime > now) return;

        if (!basicCondition(minecraft) || !settingCondition(minecraft)) {
            return;
        }
        if (notUsingItem.isEnabled() && minecraft.thePlayer.isUsingItem()) {
            return;
        }

        if (!CombatActionCoordinator.tryAcquire("KillAura", target)) return;
        if (attackingEntity != null && minecraft.playerController != null) {
            minecraft.playerController.attackEntity(minecraft.thePlayer, attackingEntity);
            minecraft.thePlayer.swingItem();
            recordSuccessfulAttack(minecraft, attackingEntity);
        } else {
            minecraft.thePlayer.swingItem();
        }
        nextClickTime = now + (nextDelay() * 1000000L);
    }

    public boolean shouldOverrideMouseOver() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return isEnabled()
            && basicCondition(minecraft)
            && attackingEntity != null
            && target == attackingEntity
            && targetDistance <= swingRange.getValue();
    }

    public boolean isActivelyOwningRotation() {
        return isEnabled() && target != null && targetDistance <= aimRange.getValue();
    }

    public boolean isActivelyAttacking() {
        return isEnabled() && attackingEntity != null && targetDistance <= swingRange.getValue();
    }

    @Override
    public String getHudInfo() {
        return target == null ? "No target" : target.getName() + " " + String.format(java.util.Locale.ROOT, "%.1f", targetDistance);
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewEntity = minecraft.getRenderViewEntity();
        if (viewEntity == null) {
            return;
        }

        Vec3 eyes = viewEntity.getPositionEyes(partialTicks);
        Vec3 look = viewEntity.getLook(partialTicks);
        double reach = attackRange.getValue();
        Vec3 rayEnd = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        float border = attackingEntity.getCollisionBorderSize();
        AxisAlignedBB bb = attackingEntity.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition intercept = bb.calculateIntercept(eyes, rayEnd);
        boolean inside = bb.isVecInside(eyes);
        if (!inside && intercept == null) {
            return;
        }

        Vec3 hitVec = inside ? (intercept == null ? eyes : intercept.hitVec) : intercept.hitVec;
        MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(eyes, hitVec, false, false, true);
        if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        if (!hitThroughEntities.isEnabled() && KillAuraRotationUtils.isPathBlockedByEntity(eyes, hitVec, attackingEntity)) {
            return;
        }

        minecraft.objectMouseOver = new MovingObjectPosition(attackingEntity, hitVec);
        minecraft.pointedEntity = attackingEntity;
        if (pointedEntityField != null) {
            try {
                pointedEntityField.set(minecraft.entityRenderer, attackingEntity);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private void handleTarget(Minecraft minecraft) {
        double maxRange = Math.max(attackRange.getValue(), aimRange.getValue());
        List<KillAuraTarget> candidates = new ArrayList<KillAuraTarget>();
        for (EntityLivingBase living : CombatTargetService.candidates(minecraft)) {
            Candidate candidate = getCandidateTarget(minecraft, living, maxRange);
            if (candidate == null) {
                continue;
            }

            KillAuraTarget auraTarget = buildKillAuraTarget(candidate.entity, candidate.distance, maxRange);
            if (auraTarget != null) {
                candidates.add(auraTarget);
            }
        }

        Collections.sort(candidates, targetComparator());

        double attackRangeValue = attackRange.getValue();
        List<KillAuraTarget> attackTargets = new ArrayList<KillAuraTarget>();
        for (KillAuraTarget candidate : candidates) {
            if (candidate.distance <= attackRangeValue) {
                attackTargets.add(candidate);
            }
        }

        if (!attackTargets.isEmpty()) {
            KillAuraTarget selectedAttackTarget = selectAttackTarget(minecraft, attackTargets);
            if (selectedAttackTarget != null) {
                setTarget(selectedAttackTarget.entity);
                return;
            }
            return;
        }

        if (!candidates.isEmpty()) {
            setTarget(candidates.get(0).entity);
            return;
        }

        setTarget(null);
    }

    private Candidate getCandidateTarget(Minecraft minecraft, Entity entity, double maxRange) {
        if (!(entity instanceof EntityLivingBase)) {
            return null;
        }

        EntityLivingBase living = (EntityLivingBase) entity;
        if (!CombatTargetService.isValid(minecraft, living, targetType.getValue().targetsPlayers(),
                targetType.getValue().targetsMobs(), targetInvis.isEnabled(), false, true, maxRange)) {
            return null;
        }

        return new Candidate(living, CombatTargetService.distanceToHitbox(minecraft, living));
    }

    private KillAuraTarget buildKillAuraTarget(EntityLivingBase entity, double distanceToBoundingBox, double maxRange) {
        if (!KillAuraRotationUtils.hasValidAimPoint(entity, 100.0D, 100.0D, maxRange, false, hitThroughEntities.isEnabled())) {
            return null;
        }

        return new KillAuraTarget(entity, distanceToBoundingBox, entity.getHealth(),
            angleDifference(entity), entity.getEntityId());
    }

    private Comparator<KillAuraTarget> targetComparator() {
        final TargetPriority priority = targetPriority.getValue();
        return new Comparator<KillAuraTarget>() {
            @Override
            public int compare(KillAuraTarget left, KillAuraTarget right) {
                int result;
                if (priority == TargetPriority.DISTANCE) {
                    result = Double.compare(left.distance, right.distance);
                } else if (priority == TargetPriority.ANGLE) {
                    result = Double.compare(left.angle, right.angle);
                } else {
                    result = Float.compare(left.health, right.health);
                }
                if (result != 0) return result;
                result = Double.compare(left.distance, right.distance);
                if (result != 0) return result;
                return Integer.compare(left.entityId, right.entityId);
            }
        };
    }

    private double angleDifference(EntityLivingBase entity) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null || entity == null) return Double.MAX_VALUE;
        com.razorclient.runtime.EntityRecord record = getContext().getEntities().find(entity.getEntityId());
        float yaw = record == null ? minecraft.thePlayer.rotationYaw : record.getYawToCenter();
        float pitch = record == null ? minecraft.thePlayer.rotationPitch : record.getPitchToCenter();
        if (record == null) {
            float[] rotations = KillAuraRotationUtils.getRotations(entity, 100.0D, 100.0D,
                minecraft.thePlayer.rotationYaw, minecraft.thePlayer.rotationPitch);
            if (rotations == null) return Double.MAX_VALUE;
            yaw = rotations[0];
            pitch = rotations[1];
        }
        float yawDifference = net.minecraft.util.MathHelper.wrapAngleTo180_float(
            yaw - minecraft.thePlayer.rotationYaw);
        float pitchDifference = pitch - minecraft.thePlayer.rotationPitch;
        return Math.sqrt(yawDifference * yawDifference + pitchDifference * pitchDifference);
    }

    private KillAuraTarget selectAttackTarget(Minecraft minecraft, List<KillAuraTarget> attackTargets) {
        int ticksExisted = minecraft.thePlayer.ticksExisted;
        int switchDelayTicks = Math.max(1, switchDelay.getValue() / 50);
        long noHitTicks = (long) Math.min(attackTargets.size(), targets.getValue()) * switchDelayTicks;

        for (KillAuraTarget candidate : attackTargets) {
            Integer firstHitTick = hitMap.get(Integer.valueOf(candidate.entityId));
            if (firstHitTick == null || ticksExisted - firstHitTick.intValue() >= switchDelayTicks) {
                continue;
            }
            return candidate;
        }

        for (KillAuraTarget candidate : attackTargets) {
            Integer firstHitTick = hitMap.get(Integer.valueOf(candidate.entityId));
            if (firstHitTick == null || ticksExisted >= firstHitTick.intValue() + noHitTicks) {
                return candidate;
            }
        }

        return null;
    }

    private void recordSuccessfulAttack(Minecraft minecraft, EntityLivingBase attacked) {
        int tick = minecraft.thePlayer.ticksExisted;
        java.util.Iterator<Map.Entry<Integer, Integer>> iterator = hitMap.entrySet().iterator();
        while (iterator.hasNext()) {
            if (tick - iterator.next().getValue().intValue() > 200) iterator.remove();
        }
        if (hitMap.size() >= 256) hitMap.clear();
        hitMap.put(Integer.valueOf(attacked.getEntityId()), Integer.valueOf(tick));
    }

    private boolean basicCondition(Minecraft minecraft) {
        return minecraft != null
            && minecraft.thePlayer != null
            && minecraft.theWorld != null
            && !minecraft.thePlayer.isDead;
    }

    private boolean settingCondition(Minecraft minecraft) {
        if (disableInInventory.isEnabled() && minecraft.currentScreen != null) {
            return false;
        }
        if (weaponOnly.isEnabled() && !isHoldingWeapon(minecraft)) {
            return false;
        }
        return !disableWhileMining.isEnabled() || !isMining(minecraft);
    }

    private boolean isHoldingWeapon(Minecraft minecraft) {
        if (minecraft.thePlayer == null || minecraft.thePlayer.getHeldItem() == null) {
            return false;
        }

        Item item = minecraft.thePlayer.getHeldItem().getItem();
        return item instanceof ItemSword || item == Items.stick;
    }

    private boolean isMining(Minecraft minecraft) {
        int keyCode = minecraft.gameSettings.keyBindAttack.getKeyCode();
        if (keyCode == 0) {
            return false;
        }

        boolean attackDown = keyCode < 0 ? Mouse.isButtonDown(keyCode + 100) : Keyboard.isKeyDown(keyCode);
        if (!attackDown) {
            return false;
        }

        double reach = minecraft.playerController.getBlockReachDistance();
        Vec3 eyes = minecraft.thePlayer.getPositionEyes(1.0F);
        Vec3 look = KillAuraRotationUtils.getVectorForRotation(minecraft.thePlayer.rotationPitch, minecraft.thePlayer.rotationYaw);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        if (rayTraceEntity(minecraft, eyes, end) != null) {
            return false;
        }

        MovingObjectPosition blockHit = minecraft.theWorld.rayTraceBlocks(eyes, end, false, false, false);
        return blockHit != null
            && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && blockHit.getBlockPos() != null;
    }

    private Entity rayTraceEntity(Minecraft minecraft, Vec3 start, Vec3 end) {
        Vec3 delta = end.subtract(start);
        EntityPlayer player = minecraft.thePlayer;
        AxisAlignedBB searchBox = player.getEntityBoundingBox().addCoord(delta.xCoord, delta.yCoord, delta.zCoord).expand(1.0D, 1.0D, 1.0D);
        List<?> entities = minecraft.theWorld.getEntitiesWithinAABBExcludingEntity(player, searchBox);
        Entity closestEntity = null;
        double closestDistance = end.distanceTo(start);

        for (Object object : entities) {
            if (!(object instanceof Entity)) {
                continue;
            }

            Entity entity = (Entity) object;
            if (entity == player || !entity.canBeCollidedWith()) {
                continue;
            }

            float border = entity.getCollisionBorderSize();
            AxisAlignedBB bb = entity.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition hit = bb.calculateIntercept(start, end);
            if (bb.isVecInside(start)) {
                return entity;
            }
            if (hit == null) {
                continue;
            }

            double distance = start.distanceTo(hit.hitVec);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestEntity = entity;
            }
        }

        return closestEntity;
    }

    private long nextDelay() {
        int cps = Math.max(1, (int) targetCps.getValue());
        int baseDelay = 1000 / cps;
        int finalDelay = baseDelay + (random.nextInt(21) - 10);
        return Math.max(33, Math.min(180, finalDelay));
    }

    private void applyVisibleRotation(Minecraft minecraft, float yaw, float pitch) {
        float clampedPitch = KillAuraRotationUtils.clampPitch(pitch);
        minecraft.thePlayer.prevRotationYaw = minecraft.thePlayer.rotationYaw;
        minecraft.thePlayer.prevRotationPitch = minecraft.thePlayer.rotationPitch;
        minecraft.thePlayer.prevRotationYawHead = minecraft.thePlayer.rotationYawHead;
        minecraft.thePlayer.prevRenderYawOffset = minecraft.thePlayer.renderYawOffset;
        minecraft.thePlayer.rotationYaw = yaw;
        minecraft.thePlayer.rotationPitch = clampedPitch;
        minecraft.thePlayer.rotationYawHead = yaw;
        minecraft.thePlayer.renderYawOffset = yaw;
    }

    private void setTarget(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) {
            clearTargetState();
            return;
        }

        target = (EntityLivingBase) entity;
        getContext().getTargetPublications().publish(getScope().getOwnerToken(), target.getEntityId(), 100,
            getContext().getTick());
    }

    private void clearTargetState() {
        ClientRotationHelper.get().clearRequestedRotations("KillAura");
        target = null;
        attackingEntity = null;
        targetDistance = Double.MAX_VALUE;
        nextClickTime = 0L;
    }

    private float resolveBaseYaw(Minecraft minecraft) {
        return Float.isNaN(KillAuraRotationUtils.serverRotations[0]) ? minecraft.thePlayer.rotationYaw : KillAuraRotationUtils.serverRotations[0];
    }

    private float resolveBasePitch(Minecraft minecraft) {
        return Float.isNaN(KillAuraRotationUtils.serverRotations[1]) ? minecraft.thePlayer.rotationPitch : KillAuraRotationUtils.serverRotations[1];
    }

    private void registerForge() {
        if (forgeRegistered) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(this);
        forgeRegistered = true;
    }

    private void unregisterForge() {
        if (!forgeRegistered) {
            return;
        }
        MinecraftForge.EVENT_BUS.unregister(this);
        forgeRegistered = false;
    }

    private static java.lang.reflect.Field findRendererField(String... names) {
        try {
            java.lang.reflect.Field field = ReflectionHelper.findField(EntityRenderer.class, names);
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class Candidate {
        private final EntityLivingBase entity;
        private final double distance;

        private Candidate(EntityLivingBase entity, double distance) {
            this.entity = entity;
            this.distance = distance;
        }
    }

    private static final class KillAuraTarget {
        private final EntityLivingBase entity;
        private final double distance;
        private final float health;
        private final double angle;
        private final int entityId;

        private KillAuraTarget(EntityLivingBase entity, double distance, float health, double angle, int entityId) {
            this.entity = entity;
            this.distance = distance;
            this.health = health;
            this.angle = angle;
            this.entityId = entityId;
        }
    }

    private enum RotationMode {
        SILENT("Silent"),
        VISIBLE("Visible");

        private final String label;

        RotationMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum TargetPriority {
        HEALTH("Health"),
        DISTANCE("Distance"),
        ANGLE("Angle");

        private final String label;

        TargetPriority(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum TargetType {
        PLAYERS("Players", true, false),
        MOBS("Mobs", false, true),
        BOTH("Players + Mobs", true, true);

        private final String label;
        private final boolean players;
        private final boolean mobs;

        TargetType(String label, boolean players, boolean mobs) {
            this.label = label;
            this.players = players;
            this.mobs = mobs;
        }

        private boolean targetsPlayers() {
            return players;
        }

        private boolean targetsMobs() {
            return mobs;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
