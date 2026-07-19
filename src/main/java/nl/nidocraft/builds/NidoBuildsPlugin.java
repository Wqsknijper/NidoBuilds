package nl.nidocraft.builds;

import nl.nidocraft.builds.command.BuildCommand;
import nl.nidocraft.builds.command.BuildUploadCommand;
import nl.nidocraft.builds.storage.BuildRepository;
import nl.nidocraft.builds.ui.BuildScoreboard;
import nl.nidocraft.builds.ui.SignPrompt;
import nl.nidocraft.builds.ui.WorldMenu;
import nl.nidocraft.builds.upload.UploadService;
import nl.nidocraft.builds.world.BuildActivityListener;
import nl.nidocraft.builds.world.BuildWorldService;
import nl.nidocraft.builds.world.SchematicService;
import nl.nidocraft.builds.world.VoidChunkGenerator;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class NidoBuildsPlugin extends JavaPlugin implements Listener {
    private BuildRepository repository;
    private BuildWorldService worlds;
    private UploadService uploads;
    private SignPrompt signs;
    private BuildScoreboard scoreboard;
    private final VoidChunkGenerator generator = new VoidChunkGenerator();

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            String mongoUri = System.getenv().getOrDefault("NIDOCORE_MONGODB_URI", getConfig().getString("mongodb.uri"));
            String database = System.getenv().getOrDefault("NIDOCORE_MONGODB_DATABASE", getConfig().getString("mongodb.database", "nidocraft"));
            Path storageRoot = Path.of(Objects.requireNonNull(getConfig().getString("storage-root", "../../data/builds"))).toAbsolutePath().normalize();
            repository = new BuildRepository(mongoUri, database);
            SchematicService schematics = new SchematicService(storageRoot);
            worlds = new BuildWorldService(this, repository, schematics, storageRoot, generator);
            ensureLobby();

            signs = new SignPrompt(this);
            WorldMenu menu = new WorldMenu(this, repository, worlds, signs);
            BuildCommand build = new BuildCommand(repository, worlds, menu, getConfig().getInt("default-build-radius", 64));
            PluginCommand buildCommand = Objects.requireNonNull(getCommand("build")); buildCommand.setExecutor(build); buildCommand.setTabCompleter(build);
            Bukkit.getPluginManager().registerEvents(signs, this);
            Bukkit.getPluginManager().registerEvents(menu, this);
            Bukkit.getPluginManager().registerEvents(new BuildActivityListener(repository), this);
            Bukkit.getPluginManager().registerEvents(this, this);

            if (getConfig().getBoolean("upload.enabled", true)) {
                uploads = new UploadService(this, repository, schematics, storageRoot); uploads.start();
                Objects.requireNonNull(getCommand("buildupload")).setExecutor(new BuildUploadCommand(uploads, repository, worlds, schematics));
            }
            long autosave = Math.max(1, getConfig().getLong("autosave-minutes", 5)) * 60L * 20L;
            Bukkit.getScheduler().runTaskTimer(this, worlds::autosave, autosave, autosave);
            if (getConfig().getBoolean("scoreboard.enabled", true)) {
                scoreboard = new BuildScoreboard(repository); Bukkit.getScheduler().runTaskTimer(this, (Runnable) scoreboard::update, 20L, 20L);
            }
            getLogger().info("NidoBuilds enabled: immutable schematic versions, Mongo metadata, menus and secure single-use uploads.");
        } catch (Exception exception) {
            getLogger().severe("NidoBuilds could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void ensureLobby() {
        String name = getConfig().getString("build-lobby-world", "build-lobby");
        World lobby = Bukkit.getWorld(name); if (lobby == null) lobby = new WorldCreator(name).generator(generator).generateStructures(false).createWorld();
        if (lobby == null) throw new IllegalStateException("Build lobby could not be created.");
        int y = Math.max(64, lobby.getMinHeight() + 2);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) lobby.getBlockAt(x, y - 1, z).setType(Material.BARRIER, false);
        lobby.setSpawnLocation(new Location(lobby, 0.5, y, 0.5)); lobby.setTime(6000); lobby.setAutoSave(true);
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nidobuilds.use")) { player.setGameMode(GameMode.CREATIVE); player.setAllowFlight(true); }
        String lobbyName = getConfig().getString("build-lobby-world", "build-lobby"); World lobby = Bukkit.getWorld(lobbyName);
        if (lobby != null && !player.getWorld().getName().startsWith("build_")) Bukkit.getScheduler().runTask(this, () -> player.teleport(lobby.getSpawnLocation()));
        if (scoreboard != null) scoreboard.update(player);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { if (scoreboard != null) scoreboard.remove(event.getPlayer()); }

    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return generator; }

    @Override public void onDisable() {
        if (signs != null) signs.cancelAll();
        if (uploads != null) uploads.close();
        if (worlds != null && repository != null) worlds.autosave();
        if (repository != null) repository.close();
    }
}
