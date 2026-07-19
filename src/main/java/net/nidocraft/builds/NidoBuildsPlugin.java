package net.nidocraft.builds;

import net.nidocraft.builds.command.BuildCommand;
import net.nidocraft.builds.command.BuildUploadCommand;
import net.nidocraft.builds.model.BuildWorld;
import net.nidocraft.builds.storage.BuildRepository;
import net.nidocraft.builds.ui.SignPrompt;
import net.nidocraft.builds.ui.WorldMenu;
import net.nidocraft.builds.upload.UploadService;
import net.nidocraft.builds.world.BuildActivityListener;
import net.nidocraft.builds.world.BuildWorldService;
import net.nidocraft.builds.world.BuildGameRules;
import net.nidocraft.builds.world.SchematicService;
import net.nidocraft.builds.world.VoidChunkGenerator;
import net.nidocraft.builds.world.SpawnVisualizer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class NidoBuildsPlugin extends JavaPlugin implements Listener {
    private BuildRepository repository;
    private BuildWorldService worlds;
    private UploadService uploads;
    private SignPrompt signs;
    private SpawnVisualizer spawnVisualizer;
    private final VoidChunkGenerator generator = new VoidChunkGenerator();
    private final Map<UUID, String> deathWorlds = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            String mongoUri = System.getenv().getOrDefault("NIDOCORE_MONGODB_URI", getConfig().getString("mongodb.uri"));
            String database = System.getenv().getOrDefault("NIDOCORE_MONGODB_DATABASE", getConfig().getString("mongodb.database", "nidocraft"));
            Path storageRoot = Path.of(Objects.requireNonNull(getConfig().getString("storage-root", "../../data/builds"))).toAbsolutePath().normalize();
            repository = new BuildRepository(mongoUri, database);
            SchematicService schematics = new SchematicService(storageRoot);
            worlds = new BuildWorldService(this, repository, schematics, storageRoot, generator);
            spawnVisualizer = new SpawnVisualizer(this);
            worlds.setSpawnVisualizer(spawnVisualizer);
            ensureLobby();

            signs = new SignPrompt(this);
            if (getConfig().getBoolean("upload.enabled", true)) {
                uploads = new UploadService(this, repository, schematics, storageRoot); uploads.start();
                Objects.requireNonNull(getCommand("buildupload")).setExecutor(new BuildUploadCommand(uploads, repository, worlds, schematics));
            }
            WorldMenu menu = new WorldMenu(this, repository, worlds, signs, uploads);
            BuildCommand build = new BuildCommand(repository, worlds, menu, getConfig().getInt("default-build-radius", 64));
            PluginCommand buildCommand = Objects.requireNonNull(getCommand("build")); buildCommand.setExecutor(build); buildCommand.setTabCompleter(build);
            Bukkit.getPluginManager().registerEvents(signs, this);
            Bukkit.getPluginManager().registerEvents(menu, this);
            Bukkit.getPluginManager().registerEvents(new BuildActivityListener(repository), this);
            Bukkit.getPluginManager().registerEvents(this, this);

            long autosave = Math.max(1, getConfig().getLong("autosave-minutes", 5)) * 60L * 20L;
            Bukkit.getScheduler().runTaskTimer(this, worlds::autosave, autosave, autosave);
            getLogger().info("NidoBuilds enabled: immutable schematic versions, Mongo metadata, menus and secure single-use uploads.");
        } catch (Exception exception) {
            getLogger().severe("NidoBuilds could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void ensureLobby() {
        String name = getConfig().getString("build-lobby-world", "build-lobby");
        World lobby = Bukkit.getWorld(name);
        boolean initialCreation = lobby == null && !Bukkit.getWorldContainer().toPath().resolve(name).toFile().exists();
        if (lobby == null) lobby = new WorldCreator(name).generator(generator).generateStructures(false).createWorld();
        if (lobby == null) throw new IllegalStateException("Build lobby could not be created.");
        if (initialCreation) {
            int platformY = Math.clamp(10, lobby.getMinHeight(), lobby.getMaxHeight() - 2);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) lobby.getBlockAt(x, platformY, z).setType(Material.BARRIER, false);
            lobby.setSpawnLocation(new Location(lobby, 0.5, platformY + 1, 0.5));
            lobby.setTime(6000);
        }
        lobby.setAutoSave(true);
        BuildGameRules.apply(lobby, BuildGameRules.defaults());
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nidobuilds.use")) { player.setGameMode(GameMode.CREATIVE); player.setAllowFlight(true); }
        String lobbyName = getConfig().getString("build-lobby-world", "build-lobby"); World lobby = Bukkit.getWorld(lobbyName);
        if (lobby != null && !player.getWorld().getName().startsWith("build_")) Bukkit.getScheduler().runTask(this, () -> player.teleport(lobby.getSpawnLocation()));
    }

    @EventHandler public void death(PlayerDeathEvent event) {
        deathWorlds.put(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getName());
    }

    @EventHandler public void respawn(PlayerRespawnEvent event) {
        String worldName = deathWorlds.remove(event.getPlayer().getUniqueId());
        if (worldName == null) return;
        String lobbyName = getConfig().getString("build-lobby-world", "build-lobby");
        if (worldName.equals(lobbyName)) {
            World lobby = Bukkit.getWorld(lobbyName); if (lobby != null) event.setRespawnLocation(lobby.getSpawnLocation());
            return;
        }
        if (!worldName.startsWith("build_")) return;
        String id = worldName.substring(6); BuildWorld build = repository.find(id).filter(value -> !value.deleted()).orElse(null);
        if (build == null) return;
        World world = Bukkit.getWorld(worldName); if (world == null) world = worlds.load(build);
        World respawnWorld = world;
        event.setRespawnLocation(build.defaultSpawn().map(spawn -> spawn.in(respawnWorld)).orElse(respawnWorld.getSpawnLocation()));
    }

    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return generator; }

    @Override public void onDisable() {
        if (signs != null) signs.cancelAll();
        if (uploads != null) uploads.close();
        if (worlds != null && repository != null) worlds.autosave();
        if (spawnVisualizer != null) spawnVisualizer.hideAll();
        if (repository != null) repository.close();
    }
}
