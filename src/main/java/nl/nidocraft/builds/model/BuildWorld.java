package nl.nidocraft.builds.model;

import java.util.List;
import java.util.Optional;

public record BuildWorld(
        String id, String name, BuildStatus status, String icon, String theme, int radius,
        List<String> gamemodes, List<BuildLocation> spawns, List<BuildLocation> npcs,
        String defaultSpawnId, long currentVersion, Long publishedVersion, long updatedAt, boolean deleted
) {
    public String bukkitWorldName() { return "build_" + id; }
    public Optional<BuildLocation> defaultSpawn() {
        return spawns.stream().filter(spawn -> spawn.id().equals(defaultSpawnId)).findFirst();
    }
}
