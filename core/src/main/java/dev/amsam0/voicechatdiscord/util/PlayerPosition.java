package dev.amsam0.voicechatdiscord;

/**
 * Stores a player's position (x, y, z), rotation (yaw, pitch), and world name.
 */
public class PlayerPosition {
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final String worldName;

    public PlayerPosition(double x, double y, double z, float yaw, float pitch, String worldName) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.worldName = worldName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public String getWorldName() {
        return worldName;
    }

    @Override
    public String toString() {
        return "PlayerPosition{" +
            "x=" + x +
            ", y=" + y +
            ", z=" + z +
            ", yaw=" + yaw +
            ", pitch=" + pitch +
            ", worldName='" + worldName + '\'' +
            '}';
    }
}
