package nl.nidocraft.builds.model;

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
        return new BuildLocation(id, location.x(), location.y(), location.z(), location.getYaw(), location.getPitch());
    }

    public static BuildLocation from(Document value) {
        return new BuildLocation(value.getString("id"), number(value, "x"), number(value, "y"), number(value, "z"),
                (float) number(value, "yaw"), (float) number(value, "pitch"));
    }

    private static double number(Document value, String key) {
        Number number = value.get(key, Number.class);
        return number == null ? 0 : number.doubleValue();
    }
}
