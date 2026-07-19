package nl.nidocraft.builds.world;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.nidocraft.builds.model.BuildLocation;
import nl.nidocraft.builds.model.BuildWorld;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** Runtime-only spawn markers. They are hidden before every schematic snapshot. */
public final class SpawnVisualizer {
    private final NamespacedKey markerKey;

    public SpawnVisualizer(JavaPlugin plugin) {
        markerKey = new NamespacedKey(plugin, "spawn-hologram");
    }

    public void show(BuildWorld build, World world) {
        hide(world);
        for (BuildLocation spawn : build.spawns()) {
            boolean isDefault = spawn.id().equals(build.defaultSpawnId());
            TextDisplay display = world.spawn(spawn.in(world).add(0, 1.65, 0), TextDisplay.class, hologram -> {
                Component heading = Component.text(isDefault ? "WORLD SPAWN" : "SPAWN: " + spawn.id().toUpperCase(Locale.ROOT),
                                isDefault ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                        .decorate(TextDecoration.BOLD);
                Component coordinates = Component.text(String.format(Locale.ROOT, "\n%.1f, %.1f, %.1f", spawn.x(), spawn.y(), spawn.z()), NamedTextColor.YELLOW);
                hologram.text(heading.append(coordinates));
                hologram.setBillboard(Display.Billboard.CENTER);
                hologram.setSeeThrough(true);
                hologram.setShadowed(true);
                hologram.setDefaultBackground(false);
                hologram.setPersistent(false);
                hologram.setInvulnerable(true);
                hologram.setGravity(false);
                hologram.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            });
            display.setCustomNameVisible(false);
        }
    }

    public void hide(World world) {
        for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
            if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) entity.remove();
        }
    }

    public void hideAll() {
        Bukkit.getWorlds().forEach(this::hide);
    }
}
