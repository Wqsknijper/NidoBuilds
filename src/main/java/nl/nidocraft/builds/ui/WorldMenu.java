package nl.nidocraft.builds.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.nidocraft.builds.model.BuildStatus;
import nl.nidocraft.builds.model.BuildWorld;
import nl.nidocraft.builds.storage.BuildRepository;
import nl.nidocraft.builds.world.BuildWorldService;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WorldMenu implements Listener {
    private static final int PAGE_SIZE = 36;
    private final JavaPlugin plugin;
    private final BuildRepository repository;
    private final BuildWorldService worlds;
    private final SignPrompt signs;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Inventory> menus = new HashMap<>();

    public WorldMenu(JavaPlugin plugin, BuildRepository repository, BuildWorldService worlds, SignPrompt signs) {
        this.plugin = plugin; this.repository = repository; this.worlds = worlds; this.signs = signs;
    }

    public void open(Player player) { open(player, states.getOrDefault(player.getUniqueId(), State.root())); }

    private void open(Player player, State requested) {
        State query = requested.mode == Mode.ROOT ? requested : State.root();
        List<BuildWorld> filtered = repository.list(false).stream()
                .filter(world -> query.search.isBlank() || world.name().toLowerCase(Locale.ROOT).contains(query.search) || world.id().contains(query.search))
                .filter(world -> query.filter == null || world.status() == query.filter)
                .sorted(query.sortByUpdated ? Comparator.comparingLong(BuildWorld::updatedAt).reversed() : Comparator.comparing(BuildWorld::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.clamp(query.page, 0, pages - 1);
        State state = new State(Mode.ROOT, null, page, query.search, query.filter, query.sortByUpdated);
        Inventory menu = Bukkit.createInventory(null, 45, Component.text("Build worlds", NamedTextColor.DARK_AQUA));
        int from = page * PAGE_SIZE;
        for (int index = from; index < Math.min(filtered.size(), from + PAGE_SIZE); index++) menu.setItem(index - from, worldIcon(filtered.get(index)));
        menu.setItem(36, item(Material.HOPPER, "Sort: " + (state.sortByUpdated ? "last edited" : "name"), List.of("Click to toggle")));
        menu.setItem(37, item(Material.COMPARATOR, "Filter: " + (state.filter == null ? "all" : state.filter.displayName()), List.of("Click to cycle")));
        menu.setItem(38, item(Material.OAK_SIGN, state.search.isBlank() ? "Search" : "Search: " + state.search, List.of("Enter the name on a sign", "Right-click to clear")));
        menu.setItem(39, item(Material.ARROW, "Previous page", List.of()));
        menu.setItem(40, item(Material.MAP, "Page " + (page + 1) + "/" + pages, List.of(filtered.size() + " worlds")));
        menu.setItem(41, item(Material.ARROW, "Next page", List.of()));
        menu.setItem(42, item(Material.CLOCK, "Refresh", List.of()));
        menu.setItem(44, item(Material.LIME_CONCRETE, "Create world", List.of("Choose a name and icon")));
        states.put(player.getUniqueId(), state);
        player.openInventory(menu);
        menus.put(player.getUniqueId(), menu);
    }

    private void detail(Player player, BuildWorld world) {
        Inventory menu = Bukkit.createInventory(null, 27, Component.text(world.name(), NamedTextColor.DARK_AQUA));
        menu.setItem(4, worldIcon(world));
        menu.setItem(10, item(Material.ENDER_PEARL, "Load", List.of("Teleport to this build world")));
        menu.setItem(11, item(Material.NAME_TAG, "Edit name", List.of(world.name())));
        menu.setItem(12, item(Material.GRASS_BLOCK, "Gamemodes", world.gamemodes().isEmpty() ? List.of("None") : world.gamemodes()));
        menu.setItem(13, item(Material.PAINTING, "Edit theme", List.of(world.theme())));
        menu.setItem(14, item(Material.EMERALD, "Publish", List.of("Creates an immutable version")));
        menu.setItem(15, item(Material.CHEST, "Save", List.of("Creates a manual backup")));
        menu.setItem(16, item(Material.BARRIER, "Delete", List.of("Final backup is kept forever")));
        menu.setItem(17, item(Material.REPEATER, "Game rules", List.of(world.gameRules().size() + " configured", "Defaults are deployment-safe")));
        menu.setItem(22, item(Material.ARROW, "Back", List.of()));
        states.put(player.getUniqueId(), new State(Mode.DETAIL, world.id(), 0, "", null, false));
        player.openInventory(menu);
        menus.put(player.getUniqueId(), menu);
    }

    private void gamemodes(Player player, BuildWorld world) {
        Inventory menu = Bukkit.createInventory(null, 27, Component.text("Gamemodes: " + world.name(), NamedTextColor.DARK_AQUA));
        int slot = 0;
        for (Document game : repository.gamemodes()) {
            String id = game.getString("_id"); boolean selected = world.gamemodes().contains(id);
            List<String> lore = new ArrayList<>(); lore.add(selected ? "Selected" : "Not selected");
            lore.add("Left-click: toggle"); lore.add("Right-click: make active published map");
            if (world.id().equals(game.getString("activeWorldId"))) lore.add("ACTIVE for new services");
            menu.setItem(slot++, item(selected ? Material.LIME_DYE : Material.GRAY_DYE, game.getString("name") + " (" + id + ")", lore));
        }
        menu.setItem(22, item(Material.ARROW, "Back", List.of()));
        states.put(player.getUniqueId(), new State(Mode.GAMEMODES, world.id(), 0, "", null, false));
        player.openInventory(menu);
        menus.put(player.getUniqueId(), menu);
    }

    private void confirmDelete(Player player, BuildWorld world) {
        Inventory menu = Bukkit.createInventory(null, 27, Component.text("Delete " + world.name() + "?", NamedTextColor.RED));
        menu.setItem(11, item(Material.LIME_CONCRETE, "Cancel", List.of()));
        menu.setItem(15, item(Material.RED_CONCRETE, "Delete permanently", List.of("Schematic backups stay forever")));
        states.put(player.getUniqueId(), new State(Mode.DELETE, world.id(), 0, "", null, false));
        player.openInventory(menu);
        menus.put(player.getUniqueId(), menu);
    }

    private void gamerules(Player player, BuildWorld world) {
        Inventory menu = Bukkit.createInventory(null, 27, Component.text("Game rules: " + world.name(), NamedTextColor.DARK_AQUA));
        List<Map.Entry<String, String>> entries = world.gameRules().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        for (int slot = 0; slot < Math.min(18, entries.size()); slot++) {
            Map.Entry<String, String> entry = entries.get(slot); boolean bool = entry.getValue().equals("true") || entry.getValue().equals("false");
            menu.setItem(slot, item(bool ? Material.LEVER : Material.COMPARATOR, entry.getKey(), bool
                    ? List.of("Value: " + entry.getValue(), "Click to toggle")
                    : List.of("Value: " + entry.getValue(), "Left/right: +1/-1", "Shift: +10/-10")));
        }
        menu.setItem(22, item(Material.ARROW, "Back", List.of()));
        states.put(player.getUniqueId(), new State(Mode.GAMERULES, world.id(), 0, "", null, false));
        player.openInventory(menu); menus.put(player.getUniqueId(), menu);
    }

    private void chooseIcon(Player player, String id) {
        Inventory menu = Bukkit.createInventory(null, 27, Component.text("Choose icon for " + id, NamedTextColor.DARK_AQUA));
        List<Material> icons = List.of(Material.GRASS_BLOCK, Material.BRICKS, Material.OAK_LOG, Material.STONE_BRICKS, Material.COBBLESTONE_WALL,
                Material.DIAMOND_BLOCK, Material.PAINTING, Material.BEACON, Material.REDSTONE_BLOCK, Material.SANDSTONE, Material.NETHER_BRICKS,
                Material.END_STONE_BRICKS, Material.WATER_BUCKET, Material.CHERRY_LOG, Material.MOSS_BLOCK, Material.COPPER_BLOCK, Material.SNOW_BLOCK, Material.BLACKSTONE);
        int slot = 0; for (Material icon : icons) menu.setItem(slot++, item(icon, icon.key().value(), List.of("Click to create")));
        states.put(player.getUniqueId(), new State(Mode.ICON, id, 0, "", null, false));
        player.openInventory(menu);
        menus.put(player.getUniqueId(), menu);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        State state = states.get(player.getUniqueId());
        if (state == null || menus.get(player.getUniqueId()) != event.getView().getTopInventory() || event.getClickedInventory() != event.getView().getTopInventory()) return;
        event.setCancelled(true);
        int slot = event.getSlot();
        try {
            switch (state.mode) {
                case ROOT -> rootClick(player, state, slot, event.isRightClick());
                case DETAIL -> detailClick(player, repository.find(state.worldId).orElseThrow(), slot);
                case DELETE -> { if (slot == 11) detail(player, repository.find(state.worldId).orElseThrow()); else if (slot == 15) { requireDelete(player); worlds.delete(state.worldId, player.getUniqueId()); player.sendMessage(Component.text("World deleted; final backup retained forever.", NamedTextColor.GREEN)); open(player, State.root()); } }
                case GAMEMODES -> gamemodeClick(player, state, slot, event.isRightClick());
                case GAMERULES -> gameruleClick(player, state, slot, event.isRightClick(), event.isShiftClick());
                case ICON -> { if (slot < 18 && event.getCurrentItem() != null) { requireManage(player); BuildWorld created = worlds.create(state.worldId, prettify(state.worldId), event.getCurrentItem().getType().name(), player.getUniqueId(), plugin.getConfig().getInt("default-build-radius", 64)); detail(player, created); } }
            }
        } catch (Exception exception) { player.sendMessage(Component.text(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), NamedTextColor.RED)); }
    }

    private void rootClick(Player player, State state, int slot, boolean rightClick) {
        if (slot < 36) {
            List<BuildWorld> values = repository.list(false).stream().filter(world -> state.search.isBlank() || world.name().toLowerCase(Locale.ROOT).contains(state.search) || world.id().contains(state.search))
                    .filter(world -> state.filter == null || world.status() == state.filter)
                    .sorted(state.sortByUpdated ? Comparator.comparingLong(BuildWorld::updatedAt).reversed() : Comparator.comparing(BuildWorld::name, String.CASE_INSENSITIVE_ORDER)).toList();
            int index = state.page * PAGE_SIZE + slot; if (index < values.size()) detail(player, values.get(index)); return;
        }
        if (slot == 36) open(player, state.withSort(!state.sortByUpdated));
        else if (slot == 37) { BuildStatus next = state.filter == null ? BuildStatus.EMPTY : state.filter == BuildStatus.PUBLISHED ? null : BuildStatus.values()[state.filter.ordinal() + 1]; if (next == BuildStatus.DELETED) next = null; open(player, state.withFilter(next)); }
        else if (slot == 38) { if (rightClick) open(player, state.withSearch("")); else { player.closeInventory(); signs.open(player, "world search", value -> open(player, state.withSearch(value.toLowerCase(Locale.ROOT)))); } }
        else if (slot == 39) open(player, state.withPage(state.page - 1));
        else if (slot == 41) open(player, state.withPage(state.page + 1));
        else if (slot == 42) open(player, state);
        else if (slot == 44) { requireManage(player); player.closeInventory(); signs.open(player, "new world id", value -> { String id = value.toLowerCase(Locale.ROOT).replace(' ', '-'); chooseIcon(player, id); }); }
    }

    private void detailClick(Player player, BuildWorld world, int slot) throws Exception {
        if (slot == 10) { org.bukkit.World loaded = worlds.load(world); player.teleport(world.defaultSpawn().map(spawn -> spawn.in(loaded)).orElse(loaded.getSpawnLocation())); player.sendMessage(Component.text("Loaded " + world.name(), NamedTextColor.GREEN)); }
        else if (slot == 11) { requireManage(player); player.closeInventory(); signs.open(player, "new name", value -> { repository.setText(world.id(), "name", value, player.getUniqueId()); detail(player, repository.find(world.id()).orElseThrow()); }); }
        else if (slot == 12) gamemodes(player, world);
        else if (slot == 13) { requireManage(player); player.closeInventory(); signs.open(player, "new theme", value -> { repository.setText(world.id(), "theme", value, player.getUniqueId()); detail(player, repository.find(world.id()).orElseThrow()); }); }
        else if (slot == 14) { requirePublish(player); long version = worlds.publish(world.id(), player.getUniqueId()).number(); player.sendMessage(Component.text("Published as version " + version, NamedTextColor.GREEN)); detail(player, repository.find(world.id()).orElseThrow()); }
        else if (slot == 15) { long version = worlds.save(world.id(), "manual", player.getUniqueId()).number(); player.sendMessage(Component.text("Saved backup version " + version, NamedTextColor.GREEN)); detail(player, repository.find(world.id()).orElseThrow()); }
        else if (slot == 16) { requireDelete(player); confirmDelete(player, world); }
        else if (slot == 17) gamerules(player, world);
        else if (slot == 22) open(player, State.root());
    }

    private void gamemodeClick(Player player, State state, int slot, boolean activate) {
        BuildWorld world = repository.find(state.worldId).orElseThrow();
        List<Document> games = repository.gamemodes();
        if (slot == 22) { detail(player, world); return; }
        if (slot >= games.size()) return;
        requirePublish(player);
        String id = games.get(slot).getString("_id");
        if (activate) repository.activateForGamemode(world.id(), id, player.getUniqueId()); else repository.toggleGamemode(world.id(), id, player.getUniqueId());
        gamemodes(player, repository.find(world.id()).orElseThrow());
    }

    private void gameruleClick(Player player, State state, int slot, boolean rightClick, boolean shift) {
        BuildWorld world = repository.find(state.worldId).orElseThrow();
        if (slot == 22) { detail(player, world); return; }
        List<Map.Entry<String, String>> entries = world.gameRules().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        if (slot < 0 || slot >= entries.size() || slot >= 18) return;
        Map.Entry<String, String> entry = entries.get(slot); String value;
        if (entry.getValue().equals("true") || entry.getValue().equals("false")) value = Boolean.toString(!Boolean.parseBoolean(entry.getValue()));
        else { int current = Integer.parseInt(entry.getValue()); int amount = shift ? 10 : 1; value = Integer.toString(current + (rightClick ? -amount : amount)); }
        repository.setGameRule(world.id(), entry.getKey(), value, player.getUniqueId());
        worlds.load(repository.find(world.id()).orElseThrow());
        gamerules(player, repository.find(world.id()).orElseThrow());
    }

    private ItemStack worldIcon(BuildWorld world) {
        Material material = Material.matchMaterial(world.icon()); if (material == null || !material.isItem()) material = Material.MAP;
        List<String> lore = new ArrayList<>(List.of("ID: " + world.id(), "Status: " + world.status().displayName(), "Theme: " + world.theme(),
                "Gamemodes: " + (world.gamemodes().isEmpty() ? "none" : String.join(", ", world.gamemodes())), "Spawns: " + world.spawns().size(), "NPCs: " + world.npcs().size(), "Version: " + world.currentVersion()));
        return item(material, world.name(), lore);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.AQUA));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList()); stack.setItemMeta(meta); return stack;
    }

    private void requireManage(Player player) { if (!player.hasPermission("nidobuilds.manage")) throw new SecurityException("Designer or higher permission required."); }
    private void requireDelete(Player player) { if (!player.hasPermission("nidobuilds.delete")) throw new SecurityException("Admin or owner permission required."); }
    private void requirePublish(Player player) { if (!player.hasPermission("nidobuilds.publish")) throw new SecurityException("Admin or owner permission required."); }
    private String prettify(String id) { String[] words = id.split("[-_]"); StringBuilder result = new StringBuilder(); for (String word : words) if (!word.isBlank()) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' '); return result.toString().trim(); }

    private enum Mode { ROOT, DETAIL, DELETE, GAMEMODES, GAMERULES, ICON }
    private record State(Mode mode, String worldId, int page, String search, BuildStatus filter, boolean sortByUpdated) {
        static State root() { return new State(Mode.ROOT, null, 0, "", null, false); }
        State withPage(int value) { return new State(mode, worldId, Math.max(0, value), search, filter, sortByUpdated); }
        State withSearch(String value) { return new State(mode, worldId, 0, value, filter, sortByUpdated); }
        State withFilter(BuildStatus value) { return new State(mode, worldId, 0, search, value, sortByUpdated); }
        State withSort(boolean value) { return new State(mode, worldId, 0, search, filter, value); }
    }
}
