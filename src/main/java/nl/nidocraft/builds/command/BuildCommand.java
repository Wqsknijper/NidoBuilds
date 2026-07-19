package nl.nidocraft.builds.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.nidocraft.builds.model.BuildLocation;
import nl.nidocraft.builds.model.BuildStatus;
import nl.nidocraft.builds.model.BuildVersion;
import nl.nidocraft.builds.model.BuildWorld;
import nl.nidocraft.builds.storage.BuildRepository;
import nl.nidocraft.builds.ui.WorldMenu;
import nl.nidocraft.builds.world.BuildWorldService;
import org.bson.Document;
import org.bukkit.Material;
import org.bukkit.GameRule;
import nl.nidocraft.builds.world.BuildGameRules;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class BuildCommand implements CommandExecutor, TabCompleter {
    private final BuildRepository repository;
    private final BuildWorldService worlds;
    private final WorldMenu menu;
    private final int defaultRadius;

    public BuildCommand(BuildRepository repository, BuildWorldService worlds, WorldMenu menu, int defaultRadius) {
        this.repository = repository; this.worlds = worlds; this.menu = menu; this.defaultRadius = defaultRadius;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command is player-only."); return true; }
        if (!player.hasPermission("nidobuilds.use")) { error(player, "Designer or higher required."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("list")) { menu.open(player); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "load" -> load(player, requireArg(args, 1, "world"));
                case "save" -> save(player, worldArgument(player, args, 1), "manual");
                case "delete" -> { require(player, "nidobuilds.delete"); BuildVersion version = worlds.delete(requireArg(args, 1, "world"), player.getUniqueId()); success(player, "Deleted; permanent backup v" + version.number() + " retained."); }
                case "publish" -> { require(player, "nidobuilds.publish"); BuildVersion version = worlds.publish(worldArgument(player, args, 1), player.getUniqueId()); success(player, "Published version " + version.number() + "."); }
                case "ready" -> { String id = worldArgument(player, args, 1); repository.setStatus(id, BuildStatus.READY_TO_PUBLISH, player.getUniqueId()); success(player, id + " is ready to publish."); }
                case "setname" -> { require(player, "nidobuilds.manage"); repository.setText(requireArg(args, 1, "world"), "name", join(args, 2), player.getUniqueId()); success(player, "Name updated."); }
                case "settheme" -> { repository.setText(requireArg(args, 1, "world"), "theme", join(args, 2), player.getUniqueId()); success(player, "Theme updated."); }
                case "spawn" -> location(player, args, true);
                case "npc" -> location(player, args, false);
                case "backup" -> backup(player, args);
                case "gamemode" -> gamemode(player, args);
                case "gamerule" -> gamerule(player, args);
                default -> help(player);
            }
        } catch (Exception exception) { error(player, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()); }
        return true;
    }

    private void create(Player player, String[] args) {
        require(player, "nidobuilds.manage");
        String id = requireArg(args, 1, "id"); String icon = args.length > 2 ? args[2] : Material.GRASS_BLOCK.name();
        if (Material.matchMaterial(icon) == null) throw new IllegalArgumentException("Invalid icon material.");
        BuildWorld world = worlds.create(id, args.length > 3 ? join(args, 3) : id, icon, player.getUniqueId(), defaultRadius);
        success(player, "Created " + world.name() + ".");
    }

    private void load(Player player, String id) {
        BuildWorld build = repository.find(id).filter(world -> !world.deleted()).orElseThrow(() -> new IllegalArgumentException("Unknown world."));
        World world = worlds.load(build);
        player.teleport(build.defaultSpawn().map(spawn -> spawn.in(world)).orElse(world.getSpawnLocation()));
        success(player, "Loaded " + build.name() + ".");
    }

    private void save(Player player, String id, String kind) throws Exception {
        BuildVersion version = worlds.save(id, kind, player.getUniqueId()); success(player, "Saved immutable version " + version.number() + ".");
    }

    private void location(Player player, String[] args, boolean spawn) {
        String type = spawn ? "spawn" : "npc";
        if (args.length < 4) throw new IllegalArgumentException("/build " + type + " <set|remove|default> <world> <id>");
        String operation = args[1].toLowerCase(Locale.ROOT); String worldId = args[2]; String id = args[3].toLowerCase(Locale.ROOT);
        BuildWorld build = repository.find(worldId).orElseThrow(() -> new IllegalArgumentException("Unknown world."));
        if (!player.getWorld().getName().equals(build.bukkitWorldName()) && operation.equals("set")) throw new IllegalStateException("Load that build world first.");
        List<BuildLocation> values = new ArrayList<>(spawn ? build.spawns() : build.npcs());
        String defaultId = build.defaultSpawnId();
        if (operation.equals("set")) {
            values.removeIf(value -> value.id().equals(id)); values.add(BuildLocation.from(id, player.getLocation()));
            if (spawn && defaultId == null) defaultId = id;
        } else if (operation.equals("remove")) {
            values.removeIf(value -> value.id().equals(id)); if (id.equals(defaultId)) defaultId = values.isEmpty() ? null : values.getFirst().id();
        } else if (spawn && operation.equals("default")) {
            if (values.stream().noneMatch(value -> value.id().equals(id))) throw new IllegalArgumentException("Unknown spawn."); defaultId = id;
        } else throw new IllegalArgumentException("Use set, remove" + (spawn ? " or default." : "."));
        repository.setLocations(worldId, spawn ? "spawns" : "npcs", values, defaultId, player.getUniqueId());
        if (spawn) worlds.refreshVisuals(repository.find(worldId).orElseThrow());
        success(player, type + " locations updated (" + values.size() + ").");
    }

    private void backup(Player player, String[] args) throws Exception {
        require(player, "nidobuilds.backup.restore");
        if (args.length < 3) throw new IllegalArgumentException("/build backup <list|load> <world> [version]");
        String worldId = args[2];
        if (args[1].equalsIgnoreCase("list")) {
            List<BuildVersion> versions = repository.versions(worldId); player.sendMessage(Component.text("Backups for " + worldId + ":", NamedTextColor.AQUA));
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
            versions.stream().limit(20).forEach(version -> player.sendMessage(Component.text("v" + version.number() + " " + version.kind() + " — " + format.format(version.createdAt()), NamedTextColor.GRAY)));
        } else if (args[1].equalsIgnoreCase("load")) {
            if (args.length < 4) throw new IllegalArgumentException("Version required."); long number = Long.parseLong(args[3]); worlds.restore(worldId, number, player.getUniqueId()); success(player, "Restored v" + number + "; a pre-restore backup was made first.");
        } else throw new IllegalArgumentException("Use list or load.");
    }

    private void gamemode(Player player, String[] args) {
        require(player, "nidobuilds.publish");
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            player.sendMessage(Component.text("Gamemodes:", NamedTextColor.AQUA));
            for (Document game : repository.gamemodes()) player.sendMessage(Component.text(game.getString("_id") + " — active: " + String.valueOf(game.getString("activeWorldId")), NamedTextColor.GRAY));
            return;
        }
        if (args.length < 4) throw new IllegalArgumentException("/build gamemode <toggle|activate> <world> <gamemode>");
        String worldId = args[2].toLowerCase(Locale.ROOT); String gamemodeId = args[3].toLowerCase(Locale.ROOT);
        if (args[1].equalsIgnoreCase("toggle")) {
            boolean selected = repository.toggleGamemode(worldId, gamemodeId, player.getUniqueId());
            if (selected) success(player, gamemodeId + " selected. Activate the published map with /worlds gamemode activate " + worldId + " " + gamemodeId + ".");
            else success(player, gamemodeId + " unselected. Its active deployment was cleared if this map was active.");
        } else if (args[1].equalsIgnoreCase("activate")) {
            repository.activateForGamemode(worldId, gamemodeId, player.getUniqueId());
            success(player, "Published " + worldId + " activated for " + gamemodeId + "; new or restarted services will load it.");
        } else throw new IllegalArgumentException("Use toggle or activate.");
    }

    private void gamerule(Player player, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("/build gamerule <list|world> [rule] [value]");
        if (args[1].equalsIgnoreCase("list")) {
            String worldId = worldArgument(player, args, 2); BuildWorld build = repository.find(worldId).orElseThrow(() -> new IllegalArgumentException("Unknown world."));
            player.sendMessage(Component.text("Game rules for " + build.name() + ":", NamedTextColor.AQUA));
            build.gameRules().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry ->
                    player.sendMessage(Component.text(entry.getKey() + " = " + entry.getValue(), NamedTextColor.GRAY)));
            return;
        }
        if (args.length < 4) throw new IllegalArgumentException("/build gamerule <world> <rule> <value>");
        String worldId = args[1].toLowerCase(Locale.ROOT); repository.setGameRule(worldId, args[2], args[3], player.getUniqueId());
        BuildWorld build = repository.find(worldId).orElseThrow(); worlds.load(build);
        success(player, args[2] + " is now " + args[3] + " for " + build.name() + ".");
    }

    private void help(Player player) {
        player.sendMessage(Component.text("NidoBuilds", NamedTextColor.AQUA));
        player.sendMessage(Component.text("/build menu | create | load | save | ready | publish | delete", NamedTextColor.GRAY));
        player.sendMessage(Component.text("/build spawn|npc | backup | gamemode | gamerule | setname | settheme", NamedTextColor.GRAY));
    }

    private String worldArgument(Player player, String[] args, int index) {
        if (args.length > index) return args[index].toLowerCase(Locale.ROOT);
        String name = player.getWorld().getName(); if (name.startsWith("build_")) return name.substring(6);
        throw new IllegalArgumentException("World argument required outside a build world.");
    }
    private String requireArg(String[] args, int index, String name) { if (args.length <= index || args[index].isBlank()) throw new IllegalArgumentException("Missing " + name + "."); return args[index].toLowerCase(Locale.ROOT); }
    private String join(String[] args, int from) { if (args.length <= from) throw new IllegalArgumentException("Missing value."); return String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim(); }
    private void require(Player player, String permission) { if (!player.hasPermission(permission)) throw new SecurityException("You do not have permission."); }
    private void success(Player player, String text) { player.sendMessage(Component.text(text, NamedTextColor.GREEN)); }
    private void error(Player player, String text) { player.sendMessage(Component.text(text, NamedTextColor.RED)); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return match(args[0], List.of("menu", "create", "load", "save", "ready", "publish", "delete", "spawn", "npc", "backup", "gamemode", "gamerule", "setname", "settheme"));
        if (args.length == 2 && List.of("load", "save", "ready", "publish", "delete", "setname", "settheme").contains(args[0].toLowerCase(Locale.ROOT))) return match(args[1], repository.list(false).stream().map(BuildWorld::id).toList());
        if (args.length == 2 && List.of("spawn", "npc").contains(args[0].toLowerCase(Locale.ROOT))) return match(args[1], args[0].equalsIgnoreCase("spawn") ? List.of("set", "remove", "default") : List.of("set", "remove"));
        if (args.length == 2 && args[0].equalsIgnoreCase("backup")) return match(args[1], List.of("list", "load"));
        if (args.length == 2 && args[0].equalsIgnoreCase("gamemode")) return match(args[1], List.of("list", "toggle", "activate"));
        if (args.length == 2 && args[0].equalsIgnoreCase("gamerule")) return match(args[1], java.util.stream.Stream.concat(java.util.stream.Stream.of("list"), repository.list(false).stream().map(BuildWorld::id)).toList());
        if (args.length == 3 && List.of("spawn", "npc", "backup", "gamemode").contains(args[0].toLowerCase(Locale.ROOT))) return match(args[2], repository.list(false).stream().map(BuildWorld::id).toList());
        if (args.length == 4 && args[0].equalsIgnoreCase("gamemode")) return match(args[3], repository.gamemodes().stream().map(value -> value.getString("_id")).toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("gamerule") && !args[1].equalsIgnoreCase("list")) return match(args[2], BuildGameRules.names());
        if (args.length == 4 && args[0].equalsIgnoreCase("gamerule")) { try { GameRule<?> rule = BuildGameRules.find(args[2]); return rule.getType() == Boolean.class ? match(args[3], List.of("true", "false")) : List.of(); } catch (IllegalArgumentException ignored) { return List.of(); } }
        return List.of();
    }
    private List<String> match(String prefix, List<String> values) { String lower = prefix.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.startsWith(lower)).toList(); }
}
