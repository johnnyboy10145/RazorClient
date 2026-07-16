package com.razorclient.combat;

import com.razorclient.RazorClient;
import com.razorclient.runtime.ResourceArbiter;
import com.razorclient.runtime.OwnerToken;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.impl.HitSelectModule;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

public final class CombatActionCoordinator {
    private CombatActionCoordinator() { }

    public static synchronized boolean tryAcquire(String requestedOwner) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null || requestedOwner == null) return false;
        ResourceArbiter arbiter = getArbiter();
        if (arbiter == null) return false;
        ResourceArbiter.Resource resource = isUseOwner(requestedOwner)
            ? ResourceArbiter.Resource.USE_ACTION : ResourceArbiter.Resource.ATTACK_ACTION;
        OwnerToken token = findOwnerToken(requestedOwner);
        return token != null && arbiter.acquire(resource, token, priority(requestedOwner), 1, null) != null;
    }

    public static boolean tryAcquire(String requestedOwner, EntityLivingBase target) {
        if (!isUseOwner(requestedOwner) && HitSelectModule.shouldSuppressAttack(target)) return false;
        return tryAcquire(requestedOwner);
    }

    public static synchronized String getOwner() {
        ResourceArbiter arbiter = getArbiter();
        if (arbiter == null) return "None";
        String attack = arbiter.getOwner(ResourceArbiter.Resource.ATTACK_ACTION);
        return "None".equals(attack) ? arbiter.getOwner(ResourceArbiter.Resource.USE_ACTION) : attack;
    }

    public static synchronized void clear() {
        ResourceArbiter arbiter = getArbiter();
        if (arbiter != null) {
            arbiter.clear(ResourceArbiter.Resource.ATTACK_ACTION);
            arbiter.clear(ResourceArbiter.Resource.USE_ACTION);
        }
    }

    private static ResourceArbiter getArbiter() {
        RazorClient client = RazorClient.getInstance();
        return client == null ? null : client.getModuleManager().getResourceArbiter();
    }

    private static OwnerToken findOwnerToken(String owner) {
        RazorClient client = RazorClient.getInstance();
        if (client == null || owner == null) return null;
        for (Module module : client.getModuleManager().getModules()) {
            if (owner.equals(module.getName())) return module.getScope().getOwnerToken();
        }
        return null;
    }

    private static boolean isUseOwner(String owner) {
        return "Clutch".equals(owner) || "LegitScaffold".equals(owner) || "RightClicker".equals(owner);
    }

    private static int priority(String owner) {
        if ("AntiFireball".equals(owner)) return 100;
        if ("KillAura".equals(owner) || "Clutch".equals(owner)) return 90;
        if ("Reach".equals(owner) || "LegitScaffold".equals(owner)) return 80;
        if ("RightClicker".equals(owner)) return 60;
        return 50;
    }
}
