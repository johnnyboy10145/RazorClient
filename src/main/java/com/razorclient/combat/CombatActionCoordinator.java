package com.razorclient.combat;

import net.minecraft.client.Minecraft;

public final class CombatActionCoordinator {
    private static Object world;
    private static int tick = Integer.MIN_VALUE;
    private static String owner = "None";

    private CombatActionCoordinator() { }

    public static synchronized boolean tryAcquire(String requestedOwner) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null || requestedOwner == null) return false;
        if (world != minecraft.theWorld || tick != minecraft.thePlayer.ticksExisted) {
            world = minecraft.theWorld;
            tick = minecraft.thePlayer.ticksExisted;
            owner = "None";
        }
        if (!"None".equals(owner)) return false;
        owner = requestedOwner;
        return true;
    }

    public static synchronized String getOwner() { return owner; }

    public static synchronized void clear() {
        world = null;
        tick = Integer.MIN_VALUE;
        owner = "None";
    }
}
