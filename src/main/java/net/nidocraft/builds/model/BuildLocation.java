package net.nidocraft.builds.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.bson.Document;

public record BuildLocation(String id, double x, double y, double z, float yaw, float pitch) {
    public Document toDocument() {
        return new Document("id", id).append("x", x).append("y", y).append("z", z)
                .append("yaw", (double) yaw).append("pitch", (double) pitch);
    }

    public Location in(World world) { return new Location(world, x, y, z, yaw, pitch); }

    public static BuildLocation from(String id, Location location) {
        return new BuildLocation(id, blockCenter(location.x()), location.y(), blockCenter(location.z()),
                nearestAngle(location.getYaw(), false), nearestAngle(location.getPitch(), true));
    }

    public static BuildLocation from(Document value) {
        return new BuildLocation(value.getString("id"), blockCenter(number(value, "x")), number(value, "y"), blockCenter(number(value, "z")),
                nearestAngle((float) number(value, "yaw"), false), nearestAngle((float) number(value, "pitch"), true));
    }

    private static double number(Document value, String key) {
        Number number = value.get(key, Number.class);
        return number == null ? 0 : number.doubleValue();
    }

    private static double blockCenter(double coordinate) { return Math.floor(coordinate) + 0.5D; }

    private static float nearestAngle(float angle, boolean pitch) {
        float rounded = Math.round(angle / 45.0F) * 45.0F;
        if (pitch) return Math.clamp(rounded, -90.0F, 90.0F);
        rounded %= 360.0F;
        if (rounded <= -180.0F) rounded += 360.0F;
        if (rounded > 180.0F) rounded -= 360.0F;
        return rounded == -0.0F ? 0.0F : rounded;
    }
}
