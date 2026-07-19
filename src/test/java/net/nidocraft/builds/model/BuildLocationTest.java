package net.nidocraft.builds.model;

import org.bukkit.Location;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BuildLocationTest {
    @Test void centersCoordinatesAndRoundsRotationToFortyFiveDegrees() {
        BuildLocation positive = BuildLocation.from("spawn", new Location(null, 0.098, 11.5, 0.206, 179.2F, 3.3F));
        assertEquals(0.5, positive.x());
        assertEquals(11.5, positive.y());
        assertEquals(0.5, positive.z());
        assertEquals(180.0F, positive.yaw());
        assertEquals(0.0F, positive.pitch());

        BuildLocation negative = BuildLocation.from("npc", new Location(null, -0.1, 4.25, -2.01, -100.4F, 4.8F));
        assertEquals(-0.5, negative.x());
        assertEquals(4.25, negative.y());
        assertEquals(-2.5, negative.z());
        assertEquals(-90.0F, negative.yaw());
        assertEquals(0.0F, negative.pitch());
    }

    @Test void normalizesPreviouslyStoredLocationsWhenTheyAreLoaded() {
        BuildLocation stored = BuildLocation.from(new Document("id", "spawn").append("x", 0.098).append("y", 11.5)
                .append("z", 0.206).append("yaw", 179.2).append("pitch", 3.3));
        assertEquals(0.5, stored.x());
        assertEquals(11.5, stored.y());
        assertEquals(0.5, stored.z());
        assertEquals(180.0F, stored.yaw());
        assertEquals(0.0F, stored.pitch());
    }
}
