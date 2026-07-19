package nl.nidocraft.builds.world;

import nl.nidocraft.builds.model.BuildStatus;
import nl.nidocraft.builds.model.BuildVersion;
import nl.nidocraft.builds.model.BuildWorld;
import nl.nidocraft.builds.storage.BuildRepository;
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

    public BuildWorldService(JavaPlugin plugin, BuildRepository repository, SchematicService schematics, Path storageRoot, VoidChunkGenerator generator) throws IOException {
        this.plugin = plugin; this.repository = repository; this.schematics = schematics; this.generator = generator;
        archivesRoot = storageRoot.resolve("deleted-world-folders").toAbsolutePath().normalize();
        Files.createDirectories(archivesRoot);
    }

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
        return world;
    }

    public BuildVersion save(String id, String kind, UUID actor) throws Exception {
        BuildWorld build = repository.find(id).filter(value -> !value.deleted()).orElseThrow(() -> new IllegalArgumentException("Onbekende world."));
        if (!saving.add(id)) throw new IllegalStateException("Deze world wordt al opgeslagen.");
        try {
            World world = load(build);
            world.save();
            BuildVersion version = schematics.save(build, world, repository.nextVersion(id), kind, actor);
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
        BuildWorld build = repository.find(id).filter(value -> !value.deleted()).orElseThrow(() -> new IllegalArgumentException("Onbekende world."));
        BuildVersion version = repository.version(id, versionNumber).orElseThrow(() -> new IllegalArgumentException("Onbekende backupversie."));
        save(id, "before-restore", actor);
        schematics.paste(version.schematic(), load(build), build);
        repository.setStatus(id, BuildStatus.EDITED, actor);
        repository.audit(actor, "WORLD_RESTORE", id, new org.bson.Document("restoredVersion", versionNumber));
    }

    public BuildVersion delete(String id, UUID actor) throws Exception {
        BuildWorld build = repository.find(id).filter(value -> !value.deleted()).orElseThrow(() -> new IllegalArgumentException("Onbekende world."));
        BuildVersion finalBackup = save(id, "deleted", actor);
        World world = Bukkit.getWorld(build.bukkitWorldName());
        Path source = world == null ? null : world.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (world != null) {
            World lobby = Bukkit.getWorld(plugin.getConfig().getString("build-lobby-world", "build-lobby"));
            Location fallback = lobby == null ? null : lobby.getSpawnLocation();
            for (Player player : new ArrayList<>(world.getPlayers())) {
                if (fallback != null) player.teleport(fallback); else player.kick();
            }
            Bukkit.unloadWorld(world, false);
        }
        if (source == null) source = worldsPathFallback(build.bukkitWorldName());
        Path serviceRoot = Path.of("").toAbsolutePath().normalize();
        if (!source.startsWith(serviceRoot)) throw new SecurityException("Worldfolder buiten de buildservice geweigerd.");
        Path destination = archivesRoot.resolve(build.id() + "-" + System.currentTimeMillis()).normalize();
        if (!destination.startsWith(archivesRoot)) throw new SecurityException("Onveilig archiefpad.");
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
            catch (Exception exception) { plugin.getLogger().severe("Autosave " + build.id() + " mislukt: " + exception.getMessage()); }
        }
    }

    private World createWorld(BuildWorld build) {
        World world = new WorldCreator(build.bukkitWorldName()).generator(generator).generateStructures(false).createWorld();
        if (world == null) throw new IllegalStateException("World kon niet worden gemaakt.");
        world.setAutoSave(true);
        BuildGameRules.apply(world, build.gameRules());
        world.setTime(6000);
        world.setSpawnLocation(0, Math.max(64, world.getMinHeight() + 2), 0);
        return world;
    }
}
