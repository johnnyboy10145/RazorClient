package com.razorclient.runtime;

/** Immutable scalar entity data. It deliberately contains no live Minecraft object reference. */
public final class EntityRecord {
    public enum Kind { PLAYER, LIVING, PROJECTILE, FIREBALL, DROPPED_ITEM }

    private final int entityId;
    private final Kind kind;
    private final String name;
    private final String teamName;
    private final String heldItemName;
    private final boolean spectator;
    private final boolean inTabList;
    private final boolean teammate;
    private final boolean invisible;
    private final boolean visible;
    private final boolean dead;
    private final double lastX;
    private final double lastY;
    private final double lastZ;
    private final double x;
    private final double y;
    private final double z;
    private final double motionX;
    private final double motionY;
    private final double motionZ;
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;
    private final double distanceToHitbox;
    private final float yawToCenter;
    private final float pitchToCenter;
    private final float health;
    private final float maxHealth;
    private final int armor;
    private final float collisionBorder;

    EntityRecord(int entityId, Kind kind, String name, String teamName, String heldItemName,
            boolean spectator, boolean inTabList, boolean teammate, boolean invisible,
            boolean visible, boolean dead, double lastX, double lastY, double lastZ,
            double x, double y, double z, double motionX, double motionY, double motionZ,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double distanceToHitbox, float yawToCenter, float pitchToCenter, float health,
            float maxHealth, int armor, float collisionBorder) {
        this.entityId = entityId;
        this.kind = kind;
        this.name = safe(name);
        this.teamName = safe(teamName);
        this.heldItemName = safe(heldItemName);
        this.spectator = spectator;
        this.inTabList = inTabList;
        this.teammate = teammate;
        this.invisible = invisible;
        this.visible = visible;
        this.dead = dead;
        this.lastX = lastX;
        this.lastY = lastY;
        this.lastZ = lastZ;
        this.x = x;
        this.y = y;
        this.z = z;
        this.motionX = motionX;
        this.motionY = motionY;
        this.motionZ = motionZ;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.distanceToHitbox = distanceToHitbox;
        this.yawToCenter = yawToCenter;
        this.pitchToCenter = pitchToCenter;
        this.health = health;
        this.maxHealth = maxHealth;
        this.armor = armor;
        this.collisionBorder = collisionBorder;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public int getEntityId() { return entityId; }
    public Kind getKind() { return kind; }
    public String getName() { return name; }
    public String getTeamName() { return teamName; }
    public String getHeldItemName() { return heldItemName; }
    public boolean isPlayer() { return kind == Kind.PLAYER; }
    public boolean isProjectile() { return kind == Kind.PROJECTILE || kind == Kind.FIREBALL; }
    public boolean isFireball() { return kind == Kind.FIREBALL; }
    public boolean isDroppedItem() { return kind == Kind.DROPPED_ITEM; }
    public boolean isSpectator() { return spectator; }
    public boolean isInTabList() { return inTabList; }
    public boolean isTeammate() { return teammate; }
    public boolean isInvisible() { return invisible; }
    public boolean isVisible() { return visible; }
    public boolean isDead() { return dead; }
    public double getLastX() { return lastX; }
    public double getLastY() { return lastY; }
    public double getLastZ() { return lastZ; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public double getMotionX() { return motionX; }
    public double getMotionY() { return motionY; }
    public double getMotionZ() { return motionZ; }
    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }
    public double getDistanceToHitbox() { return distanceToHitbox; }
    public float getYawToCenter() { return yawToCenter; }
    public float getPitchToCenter() { return pitchToCenter; }
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public int getArmor() { return armor; }
    public float getCollisionBorder() { return collisionBorder; }
    public double interpolateX(float partialTicks) { return lastX + (x - lastX) * partialTicks; }
    public double interpolateY(float partialTicks) { return lastY + (y - lastY) * partialTicks; }
    public double interpolateZ(float partialTicks) { return lastZ + (z - lastZ) * partialTicks; }
}
