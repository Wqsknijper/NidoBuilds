package net.nidocraft.builds.world;

import net.nidocraft.builds.model.BuildStatus;
import net.nidocraft.builds.storage.BuildRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildActivityListener implements Listener {
    private final BuildRepository repository;
    private final Set<String> marked = ConcurrentHashMap.newKeySet();
    public BuildActivityListener(BuildRepository repository) { this.repository = repository; }
    @EventHandler public void place(BlockPlaceEvent event) { mark(event.getBlock().getWorld().getName(), event.getPlayer().getUniqueId()); }
    @EventHandler public void breakBlock(BlockBreakEvent event) { mark(event.getBlock().getWorld().getName(), event.getPlayer().getUniqueId()); }
    private void mark(String worldName, UUID actor) {
        if (!worldName.startsWith("build_")) return; String id = worldName.substring(6);
        if (marked.add(id)) repository.setStatus(id, BuildStatus.EDITED, actor);
    }
    public void afterSave(String id) { marked.remove(id); }
}
