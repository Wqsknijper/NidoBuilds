package net.nidocraft.builds.world;

import net.nidocraft.builds.model.BuildStatus;
import net.nidocraft.builds.model.BuildVersion;
import net.nidocraft.builds.model.BuildWorld;
import net.nidocraft.builds.storage.BuildRepository;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class BuildWorldService {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{1,31}");
    private final JavaPlugin plugin;
    private final BuildRepository repository;
    private final SchematicService schematics;
    private final Path archivesRoot;
    private final VoidChunkGenerator generator;
    private final Set<String> saving = ConcurrentHashMap.newKeySet();
    private SpawnVisualizer spawnVisualizer;

    public BuildWorldService(JavaPlugin plugin, BuildRepository repository, SchematicService schematics, Path storageRoot, VoidChunkGenerator generator) throws IOException {
        this.plugin = plugin; this.repository = repository; this.schematics = schematics; this.generator = generator;
        archivesRoot = storageRoot.resolve("deleted-world-folders").toAbsolutePath().normalize();
        Files.createDirectories(archivesRoot);
    }

    public void setSpawnVisualizer(SpawnVisualizer spawnVisualizer) { this.spawnVisualizer = spawnVisualizer; }

    public BuildWorld create(String rawId, String name, String icon, UUID actor, int radius) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Id: 2-32 kleine letters/cijfers/_/-.");
        if (name.isBlank() || name.length() > 32) throw new IllegalArgumentException("Naam: 1-32 tekens.");
        BuildWorld build = repository.create(id, name.trim(), icon, Math.clamp(radius, 16, 256), actor);
        createWorld(build);
        return build;
    }

    public World load(BuildWorld build) {
        World world = Bukkit.getWorld(build.bukkitWorldName());
        if (world == null) world = createWorld(build);
        BuildGameRules.apply(world, build.gameRules());
        if (spawnVisualizer != null) spawnVisualizer.show(build, world);
        return world;
    }

    public BuildVersion save(String id, String kind, UUID actor) throws Exception {
        BuildWorld build = repository.find(id).filter(value -> !value.deleted()).orElseThrow(() -> new IllegalArgumentException("Unknown world."));
        if (!saving.add(id)) throw new IllegalStateException("This world is already being saved.");
        try {
            World world = load(build);
            if (spawnVisualizer != null) spawnVisualizer.hide(world);
            BuildVersion version;
            try {
                world.save();
                version = schematics.save(build, world, repository.nextVersion(id), kind, actor);
            } finally {
                if (spawnVisualizer != null) spawnVisualizer.show(repository.find(id).orElse(build), world);
            }
            BuildStatus status;
            if (kind.equals("publish")) status = BuildStatus.PUBLISHED;
            else {
                BuildVersion previous = repository.versions(id).stream().findFirst().orElse(null);
                status = previous != null && previous.sha256().equals(version.sha256()) ? build.status() : BuildStatus.EDITED;
            }
            repository.addVersion(version, status);
            return version;
        } finally { saving.remove(id); }
    }

    public BuildVersion publish(String id, UUID actor) throws Exception {
        BuildVersion version = save(id, "publish", actor);
        repository.publish(id, version.number(), actor);
        return version;
    }

    public void restore(String id, long versionNumber, UUID actor) throws Exception {
        BuildWorld build = repository.find(id).filter(value -> !value.deleted()).orElseThrow(() -> new IllegalArgumentException("Unknown world."));
        BuildVersion version = repository.version(id, versionNumber).orElseThrow(() -> new IllegalArgumentException("Unknown backup version."));
        save(id, "before-restore", actor);
        schematics.paste(version.schematic(), load(build), build);
        repository.setStatus(id, BuildStatus.EDITED, actor);
        repository.audit(actor, "WORLD_RESTORE", id, new org.bson.Document("restoredVersion", versionNumber));
        refreshVisuals(repository.find(id).orElse(build));
    }

    public BuildWorld restoreAsNew(String sourceId, long versionNumber, String rawId, UUID actor) throws Exception {
        BuildWorld source = repository.find(sourceId).orElseThrow(() -> new IllegalArgumentException("Unknown source world."));
        BuildVersion version = repository.version(sourceId, versionNumber).orElseThrow(() -> new IllegalArgumentException("Unknown backup version."));
        if (!Files.isRegularFile(version.schematic())) throw new IllegalStateException("The backup file is missing from disk.");
        String id = rawId.toLowerCase(Locale.ROOT).replace(' ', '-');
        String suffix = " backup v" + versionNumber;
        String baseName = source.name().substring(0, Math.min(source.name().length(), 32 - Math.min(31, suffix.length())));
        String restoredName = (baseName + suffix).substring(0, Math.min(32, baseName.length() + suffix.length()));
        BuildWorld target = create(id, restoredName, source.icon(), actor, source.radius());
        repository.setText(id, "theme", source.theme(), actor);
        repository.setLocations(id, "spawns", source.spawns(), source.defaultSpawnId(), actor);
        repository.setLocations(id, "npcs", source.npcs(), null, actor);
        for (var rule : source.gameRules().entrySet()) repository.setGameRule(id, rule.getKey(), rule.getValue(), actor);
        target = repository.find(id).orElseThrow();
        schematics.paste(version.schematic(), load(target), target);
        BuildVersion restored = save(id, "restored-from-" + sourceId + "-v" + versionNumber, actor);
        repository.audit(actor, "WORLD_RESTORE_AS_NEW", id, new org.bson.Document("sourceWorld", sourceId)
                .append("sourceVersion", versionNumber).append("newVersion", restored.number()));
        target = repository.find(id).orElseThrow();
        refreshVisuals(target);
        return target;
    }

    public void refreshVisuals(BuildWorld build) {
        World world = Bukkit.getWorld(build.bukkitWorldName());
        if (world != null && spawnVisualizer != null) spawnVisualizer.show(build, world);
    }

    public BuildVersion delete(String id, UUID actor) throws Exception {
        BuildWorld build = repository.find(id).filter(value -> !value.deleted()).orElseThrow(() -> new IllegalArgumentException("Unknown world."));
        BuildVersion finalBackup = save(id, "deleted", actor);
        World world = Bukkit.getWorld(build.bukkitWorldName());
        Path source = world == null ? null : world.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (world != null) {
            if (spawnVisualizer != null) spawnVisualizer.hide(world);
            World lobby = Bukkit.getWorld(plugin.getConfig().getString("build-lobby-world", "build-lobby"));
            Location fallback = lobby == null ? null : lobby.getSpawnLocation();
            for (Player player : new ArrayList<>(world.getPlayers())) {
                if (fallback != null) player.teleport(fallback); else player.kick();
            }
            Bukkit.unloadWorld(world, false);
        }
        if (source == null) source = worldsPathFallback(build.bukkitWorldName());
        Path serviceRoot = Path.of("").toAbsolutePath().normalize();
        if (!source.startsWith(serviceRoot)) throw new SecurityException("World directory outside the build service was rejected.");
        Path destination = archivesRoot.resolve(build.id() + "-" + System.currentTimeMillis()).normalize();
        if (!destination.startsWith(archivesRoot)) throw new SecurityException("Unsafe archive path.");
        if (Files.exists(source)) Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        repository.markDeleted(id, actor);
        return finalBackup;
    }

    private Path worldsPathFallback(String worldName) {
        return Bukkit.getWorldContainer().toPath().resolve(worldName).toAbsolutePath().normalize();
    }

    public void autosave() {
        UUID system = new UUID(0, 0);
        for (BuildWorld build : repository.list(false)) {
            if (Bukkit.getWorld(build.bukkitWorldName()) == null || saving.contains(build.id())) continue;
            try { save(build.id(), "autosave", system); }
            catch (Exception exception) { plugin.getLogger().severe("Autosave failed for " + build.id() + ": " + exception.getMessage()); }
        }
    }

    private World createWorld(BuildWorld build) {
        World world = new WorldCreator(build.bukkitWorldName()).generator(generator).generateStructures(false).createWorld();
        if (world == null) throw new IllegalStateException("World could not be created.");
        world.setAutoSave(true);
        BuildGameRules.apply(world, build.gameRules());
        world.setTime(6000);
        int platformY = Math.clamp(10, world.getMinHeight(), world.getMaxHeight() - 2);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) world.getBlockAt(x, platformY, z).setType(org.bukkit.Material.BARRIER, false);
        world.setSpawnLocation(new Location(world, 0.5, platformY + 1, 0.5));
        if (spawnVisualizer != null) spawnVisualizer.show(build, world);
        return world;
    }
}
